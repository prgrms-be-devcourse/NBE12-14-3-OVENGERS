package com.ovengers.slotkey.audit.entity

import com.ovengers.slotkey.audit.dto.AuditLogResponse
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.support.FixedClockConfig
import com.ovengers.slotkey.support.IntegrationTestSupport
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.Hibernate
import org.hibernate.proxy.HibernateProxy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.lang.reflect.Modifier
import java.time.LocalDateTime

@Transactional
@Import(FixedClockConfig::class)
class AuditLogPersistenceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @BeforeEach
    fun setUp() {
        auditLogRepository.deleteAllInBatch()
        entityManager.flush()
        entityManager.clear()
    }

    @Test
    @DisplayName("AuditLog builder로 생성한 엔티티를 저장 후 flush/clear 뒤 재조회하면 모든 필드와 @CreatedDate가 보존된다")
    fun saveFlushClearAndReload_preservesAllFieldsAndCreatedDate() {
        val toSave =
            AuditLog
                .builder()
                .actorMemberId(42L)
                .action(AuditAction.MODIFY_SPACE)
                .targetType(AuditTargetType.SPACE)
                .targetId(100L)
                .reason("공간 수용 인원 수정")
                .beforeValue("{\"capacity\":10}")
                .afterValue("{\"capacity\":20}")
                .build()

        val saved = auditLogRepository.save(toSave)
        entityManager.flush()
        val savedId = requireNotNull(saved.id)
        entityManager.clear()

        val reloaded = auditLogRepository.findById(savedId).orElseThrow()

        assertThat(reloaded.id).isEqualTo(savedId)
        assertThat(reloaded.actorMemberId).isEqualTo(42L)
        assertThat(reloaded.action).isEqualTo(AuditAction.MODIFY_SPACE)
        assertThat(reloaded.targetType).isEqualTo(AuditTargetType.SPACE)
        assertThat(reloaded.targetId).isEqualTo(100L)
        assertThat(reloaded.getTargetId()).isEqualTo(100L)
        assertThat(reloaded.reason).isEqualTo("공간 수용 인원 수정")
        assertThat(reloaded.beforeValue).isEqualTo("{\"capacity\":10}")
        assertThat(reloaded.afterValue).isEqualTo("{\"capacity\":20}")
        assertThat(reloaded.createdAt).isEqualTo(FixedClockConfig.FIXED_DATE_TIME)

        val response = AuditLogResponse.from(reloaded)
        assertThat(response.id).isEqualTo(savedId)
        assertThat(response.actorMemberId).isEqualTo(42L)
        assertThat(response.action).isEqualTo(AuditAction.MODIFY_SPACE)
        assertThat(response.targetType).isEqualTo(AuditTargetType.SPACE)
        assertThat(response.targetId).isEqualTo(100L)
        assertThat(response.reason).isEqualTo("공간 수용 인원 수정")
        assertThat(response.beforeValue).isEqualTo("{\"capacity\":10}")
        assertThat(response.afterValue).isEqualTo("{\"capacity\":20}")
        assertThat(response.createdAt).isEqualTo(FixedClockConfig.FIXED_DATE_TIME)
    }

    @Test
    @DisplayName("nullable 컬럼(actorMemberId, reason, beforeValue, afterValue)이 null인 상태로 저장 및 재조회되며 필수 필드 누락은 거부된다")
    fun saveFlushClearAndReload_supportsNullableColumnsAndValidatesRequiredFields() {
        val systemAuditLog =
            AuditLog
                .builder()
                .actorMemberId(null)
                .action(AuditAction.LOCK_MEMBER_BY_VERIFICATION)
                .targetType(AuditTargetType.MEMBER)
                .targetId(77L)
                .reason(null)
                .beforeValue(null)
                .afterValue(null)
                .build()

        val saved = auditLogRepository.save(systemAuditLog)
        entityManager.flush()
        val savedId = requireNotNull(saved.id)
        entityManager.clear()

        val reloaded = auditLogRepository.findById(savedId).orElseThrow()

        assertThat(reloaded.id).isEqualTo(savedId)
        assertThat(reloaded.actorMemberId).isNull()
        assertThat(reloaded.action).isEqualTo(AuditAction.LOCK_MEMBER_BY_VERIFICATION)
        assertThat(reloaded.targetType).isEqualTo(AuditTargetType.MEMBER)
        assertThat(reloaded.targetId).isEqualTo(77L)
        assertThat(reloaded.getTargetId()).isEqualTo(77L)
        assertThat(reloaded.reason).isNull()
        assertThat(reloaded.beforeValue).isNull()
        assertThat(reloaded.afterValue).isNull()
        assertThat(reloaded.createdAt).isEqualTo(FixedClockConfig.FIXED_DATE_TIME)

        assertThatThrownBy {
            AuditLog
                .builder()
                .targetType(AuditTargetType.SPACE)
                .targetId(1L)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            AuditLog
                .builder()
                .action(AuditAction.REGISTER_SPACE)
                .targetId(1L)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            AuditLog
                .builder()
                .action(AuditAction.REGISTER_SPACE)
                .targetType(AuditTargetType.SPACE)
                .targetId(null)
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("Hibernate 지연 참조 프록시에서 non-final getter 호출 시 프록시가 정상 초기화되고 바이트코드 계약이 유지된다")
    fun hibernateLazyReferenceProxy_initializesViaOpenGettersAndPreservesBytecodeContract() {
        val saved =
            auditLogRepository.save(
                AuditLog
                    .builder()
                    .actorMemberId(9L)
                    .action(AuditAction.SUSPEND_MEMBER)
                    .targetType(AuditTargetType.MEMBER)
                    .targetId(55L)
                    .reason("관리자 정지")
                    .beforeValue("ACTIVE")
                    .afterValue("SUSPENDED")
                    .build(),
            )
        entityManager.flush()
        val savedId = requireNotNull(saved.id)
        entityManager.clear()

        val proxy = entityManager.getReference(AuditLog::class.java, savedId)
        assertThat(proxy).isInstanceOf(HibernateProxy::class.java)
        assertThat(Hibernate.isInitialized(proxy)).isFalse()

        assertThat(proxy.targetId).isEqualTo(55L)
        assertThat(Hibernate.isInitialized(proxy)).isTrue()
        assertThat(proxy.getTargetId()).isEqualTo(55L)
        assertThat(AuditLog::class.java.getDeclaredMethod("getTargetIdValue").invoke(proxy)).isEqualTo(55L)
        assertThat(AuditLogResponse.from(proxy).targetId).isEqualTo(55L)
        assertThat(proxy.id).isEqualTo(savedId)
        assertThat(proxy.actorMemberId).isEqualTo(9L)
        assertThat(proxy.action).isEqualTo(AuditAction.SUSPEND_MEMBER)
        assertThat(proxy.targetType).isEqualTo(AuditTargetType.MEMBER)
        assertThat(proxy.reason).isEqualTo("관리자 정지")
        assertThat(proxy.beforeValue).isEqualTo("ACTIVE")
        assertThat(proxy.afterValue).isEqualTo("SUSPENDED")
        assertThat(proxy.createdAt).isEqualTo(FixedClockConfig.FIXED_DATE_TIME)

        val clazz = AuditLog::class.java
        assertThat(Modifier.isFinal(clazz.modifiers)).isFalse()
        assertThat(Modifier.isPublic(clazz.getDeclaredConstructor().modifiers)).isTrue()
        val nineArgCtor =
            clazz.getDeclaredConstructor(
                java.lang.Long::class.java,
                java.lang.Long::class.java,
                AuditAction::class.java,
                AuditTargetType::class.java,
                java.lang.Long::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                LocalDateTime::class.java,
            )
        assertThat(Modifier.isPublic(nineArgCtor.modifiers)).isTrue()

        val openGetterNames =
            listOf(
                "getId",
                "getActorMemberId",
                "getAction",
                "getTargetType",
                "getTargetIdValue",
                "getTargetId",
                "getReason",
                "getBeforeValue",
                "getAfterValue",
                "getCreatedAt",
            )
        for (getterName in openGetterNames) {
            val method = clazz.getDeclaredMethod(getterName)
            assertThat(Modifier.isPublic(method.modifiers)).`as`(getterName).isTrue()
            assertThat(Modifier.isFinal(method.modifiers)).`as`(getterName).isFalse()
        }
    }
}
