package com.ovengers.slotkey.global.idempotency

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.Hibernate
import org.hibernate.proxy.HibernateProxy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Transactional
@DisplayName("Step 4 멱등성 엔티티 JPA 프록시 검증")
class IdempotencyKeyProxyIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var idempotencyKeyRepository: IdempotencyKeyRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    @DisplayName("프록시 getter 접근 시 실제 데이터가 초기화된다")
    fun idempotencyKeyProxyInitializesOnGetterAccess() {
        // given: 테스트 회원 생성
        val member =
            memberRepository.saveAndFlush(
                Member(
                    "proxy-${UUID.randomUUID()}@test.com",
                    "프록시테스트",
                ),
            )

        // 멱등성 키 저장
        val saved =
            idempotencyKeyRepository.save(
                IdempotencyKey.of(
                    "proxy-${UUID.randomUUID()}",
                    requireNotNull(member.id),
                    "/test/step4",
                    200,
                    """{"success":true}""",
                    LocalDateTime.of(2026, 10, 25, 12, 0),
                ),
            )

        val savedId = requireNotNull(saved.id)

        // DB에 반영하고 영속성 컨텍스트 초기화
        entityManager.flush()
        entityManager.clear()

        // when: 프록시 객체 획득
        val proxy =
            entityManager.getReference(
                IdempotencyKey::class.java,
                savedId,
            )

        // then: 아직 초기화되지 않은 프록시인지 확인
        assertThat(proxy).isInstanceOf(HibernateProxy::class.java)
        assertThat(Hibernate.isInitialized(proxy)).isFalse()

        // 일반 getter 접근
        val responseStatus = proxy.responseStatus

        // 실제 DB 값과 초기화 상태 검증
        assertThat(responseStatus).isEqualTo(200)
        assertThat(Hibernate.isInitialized(proxy)).isTrue()

        // 저장된 다른 필드도 확인
        assertThat(proxy.id).isEqualTo(savedId)
        assertThat(proxy.requestPath).isEqualTo("/test/step4")
        assertThat(proxy.responseBody).isEqualTo("""{"success":true}""")
    }
}
