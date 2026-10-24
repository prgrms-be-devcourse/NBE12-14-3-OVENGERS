package com.ovengers.slotkey.global.common.entity

import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.inquiry.entity.Inquiry
import com.ovengers.slotkey.inquiry.repository.InquiryRepository
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.EntityManager
import jakarta.persistence.MappedSuperclass
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import org.springframework.transaction.annotation.Transactional
import java.lang.reflect.Modifier
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@Transactional
@Import(BaseTimeEntityPersistenceIntegrationTest.MutableClockTestConfig::class)
@DisplayName("BaseTimeEntity 영속성 및 JPA Auditing 통합 테스트")
class BaseTimeEntityPersistenceIntegrationTest : IntegrationTestSupport() {
    @TestConfiguration(proxyBeanMethods = false)
    class MutableClockTestConfig {
        @Bean
        fun mutableTestClock(): MutableClock = MutableClock(INITIAL_INSTANT, ClockConfig.DEFAULT_ZONE)

        @Bean
        @Primary
        fun testClock(mutableTestClock: MutableClock): Clock = mutableTestClock
    }

    class MutableClock(
        @Volatile private var currentInstant: Instant,
        private val zoneId: ZoneId,
    ) : Clock() {
        fun setLocalDateTime(localDateTime: LocalDateTime) {
            currentInstant = localDateTime.atZone(zoneId).toInstant()
        }

        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

        override fun instant(): Instant = currentInstant
    }

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var inquiryRepository: InquiryRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var mutableTestClock: MutableClock

    @BeforeEach
    fun resetClock() {
        mutableTestClock.setLocalDateTime(INITIAL_TIME)
    }

    @Test
    @DisplayName("Member(Java 하위 엔티티) 저장 시 createdAt·updatedAt이 채워지고 수정 후 createdAt은 유지되며 updatedAt만 갱신된다")
    fun memberPersistence_populatesTimestampsOnInsert_andAdvancesUpdatedAtOnlyOnUpdate() {
        mutableTestClock.setLocalDateTime(INITIAL_TIME)
        val uniqueSuffix = UUID.randomUUID().toString().substring(0, 8)
        val saved =
            memberRepository.save(
                Member("basetime-$uniqueSuffix@slotkey.test", "초기닉네임"),
            )
        entityManager.flush()
        entityManager.clear()

        val afterInsert = memberRepository.findById(saved.id).orElseThrow()
        assertThat(afterInsert.createdAt).isEqualTo(INITIAL_TIME)
        assertThat(afterInsert.updatedAt).isEqualTo(INITIAL_TIME)

        mutableTestClock.setLocalDateTime(UPDATED_TIME)
        afterInsert.updateNickname("변경된닉네임")
        entityManager.flush()
        entityManager.clear()

        val afterUpdate = memberRepository.findById(saved.id).orElseThrow()
        assertThat(afterUpdate.nickname).isEqualTo("변경된닉네임")
        assertThat(afterUpdate.createdAt).isEqualTo(INITIAL_TIME)
        assertThat(afterUpdate.updatedAt).isEqualTo(UPDATED_TIME)
        assertThat(afterUpdate.updatedAt).isAfter(afterUpdate.createdAt)
    }

    @Test
    @DisplayName("Inquiry(Kotlin 하위 엔티티) 저장 시 createdAt·updatedAt이 채워지고 수정 후 createdAt은 유지되며 updatedAt만 갱신된다")
    fun inquiryPersistence_populatesTimestampsOnInsert_andAdvancesUpdatedAtOnlyOnUpdate() {
        mutableTestClock.setLocalDateTime(INITIAL_TIME)
        val uniqueSuffix = UUID.randomUUID().toString().substring(0, 8)
        val member =
            memberRepository.save(
                Member("inquiry-owner-$uniqueSuffix@slotkey.test", "문의작성자"),
            )
        val savedInquiry =
            inquiryRepository.save(
                Inquiry.create(member.id, "초기 문의 제목", "초기 문의 본문"),
            )
        entityManager.flush()
        entityManager.clear()

        val afterInsert = inquiryRepository.findById(savedInquiry.id!!).orElseThrow()
        assertThat(afterInsert.createdAt).isEqualTo(INITIAL_TIME)
        assertThat(afterInsert.updatedAt).isEqualTo(INITIAL_TIME)

        mutableTestClock.setLocalDateTime(UPDATED_TIME)
        afterInsert.updateContent("수정된 문의 제목", "수정된 문의 본문")
        entityManager.flush()
        entityManager.clear()

        val afterUpdate = inquiryRepository.findById(savedInquiry.id!!).orElseThrow()
        assertThat(afterUpdate.title).isEqualTo("수정된 문의 제목")
        assertThat(afterUpdate.createdAt).isEqualTo(INITIAL_TIME)
        assertThat(afterUpdate.updatedAt).isEqualTo(UPDATED_TIME)
        assertThat(afterUpdate.updatedAt).isAfter(afterUpdate.createdAt)
    }

    @Test
    @DisplayName("BaseTimeEntity의 JVM 바이트코드·JPA 어노테이션·Java getter 계약이 유지된다")
    fun baseTimeEntity_preservesJvmContractAndJpaAnnotations() {
        val clazz = BaseTimeEntity::class.java

        assertThat(Modifier.isPublic(clazz.modifiers)).isTrue()
        assertThat(Modifier.isAbstract(clazz.modifiers)).isTrue()
        assertThat(clazz.isAnnotationPresent(MappedSuperclass::class.java)).isTrue()

        val entityListeners = clazz.getAnnotation(EntityListeners::class.java)
        assertThat(entityListeners).isNotNull
        assertThat(entityListeners.value).containsExactly(AuditingEntityListener::class)

        val defaultConstructor = clazz.getDeclaredConstructor()
        assertThat(Modifier.isPublic(defaultConstructor.modifiers)).isTrue()

        val getCreatedAt = clazz.getDeclaredMethod("getCreatedAt")
        val getUpdatedAt = clazz.getDeclaredMethod("getUpdatedAt")
        assertThat(Modifier.isPublic(getCreatedAt.modifiers)).isTrue()
        assertThat(Modifier.isFinal(getCreatedAt.modifiers)).isFalse()
        assertThat(getCreatedAt.returnType).isEqualTo(LocalDateTime::class.java)
        assertThat(Modifier.isPublic(getUpdatedAt.modifiers)).isTrue()
        assertThat(Modifier.isFinal(getUpdatedAt.modifiers)).isFalse()
        assertThat(getUpdatedAt.returnType).isEqualTo(LocalDateTime::class.java)

        val setCreatedAt = clazz.getDeclaredMethod("setCreatedAt", LocalDateTime::class.java)
        val setUpdatedAt = clazz.getDeclaredMethod("setUpdatedAt", LocalDateTime::class.java)
        assertThat(Modifier.isProtected(setCreatedAt.modifiers)).isTrue()
        assertThat(Modifier.isPublic(setCreatedAt.modifiers)).isFalse()
        assertThat(Modifier.isProtected(setUpdatedAt.modifiers)).isTrue()
        assertThat(Modifier.isPublic(setUpdatedAt.modifiers)).isFalse()

        val uniqueSuffix = UUID.randomUUID().toString().substring(0, 8)
        val member = memberRepository.save(Member("proxy-$uniqueSuffix@slotkey.test", "프록시검증"))
        entityManager.flush()
        entityManager.clear()
        val memberRef = entityManager.getReference(Member::class.java, member.id)
        assertThat(memberRef.createdAt).isEqualTo(INITIAL_TIME)
        assertThat(memberRef.updatedAt).isEqualTo(INITIAL_TIME)

        val createdAtField = clazz.getDeclaredField("createdAt")
        val createdAtColumn = createdAtField.getAnnotation(Column::class.java)
        assertThat(createdAtColumn.name).isEqualTo("created_at")
        assertThat(createdAtColumn.nullable).isFalse()
        assertThat(createdAtColumn.updatable).isFalse()

        val updatedAtField = clazz.getDeclaredField("updatedAt")
        val updatedAtColumn = updatedAtField.getAnnotation(Column::class.java)
        assertThat(updatedAtColumn.name).isEqualTo("updated_at")
    }

    companion object {
        private val INITIAL_TIME: LocalDateTime = LocalDateTime.of(2026, 10, 25, 3, 0, 0)
        private val UPDATED_TIME: LocalDateTime = LocalDateTime.of(2026, 10, 25, 4, 30, 0)
        private val INITIAL_INSTANT: Instant = INITIAL_TIME.atZone(ClockConfig.DEFAULT_ZONE).toInstant()
    }
}
