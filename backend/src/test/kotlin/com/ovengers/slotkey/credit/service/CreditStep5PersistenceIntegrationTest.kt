package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.Hibernate
import org.hibernate.proxy.HibernateProxy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Transactional
@DisplayName("Step 5 크레딧 영속성 및 원장 통합 테스트")
class CreditStep5PersistenceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Autowired
    private lateinit var creditExpirationService: CreditExpirationService

    @Autowired
    private lateinit var entityManager: EntityManager

    private val fixedAt = LocalDateTime.of(2026, 10, 25, 12, 0)

    @Test
    @DisplayName("거래 저장 후 JPA 프록시의 최초 getter 접근으로 초기화되고 값이 보존된다")
    fun creditTransactionProxyInitializesOnFirstRead() {
        // given
        val member = createMember()

        val saved =
            creditTransactionRepository.save(
                CreditTransaction(
                    member,
                    5000,
                    CreditTransactionType.ADMIN_GRANT,
                    null,
                    5000,
                    "관리자 지급",
                    fixedAt,
                ),
            )

        entityManager.flush()

        val transactionId = requireNotNull(saved.id)
        val memberId = requireNotNull(member.id)

        entityManager.clear()

        // when
        val proxy =
            entityManager.getReference(
                CreditTransaction::class.java,
                transactionId,
            )

        // then: 실제 미초기화 프록시인지 먼저 확인
        assertThat(proxy).isInstanceOf(HibernateProxy::class.java)
        assertThat(Hibernate.isInitialized(proxy)).isFalse()

        // 첫 getter 접근만으로 프록시가 초기화되어야 한다.
        val amount = proxy.amount

        assertThat(amount).isEqualTo(5000)
        assertThat(Hibernate.isInitialized(proxy)).isTrue()

        assertThat(proxy.id).isEqualTo(transactionId)
        assertThat(proxy.member.id).isEqualTo(memberId)
        assertThat(proxy.type).isEqualTo(CreditTransactionType.ADMIN_GRANT)
        assertThat(proxy.balanceAfter).isEqualTo(5000)
        assertThat(proxy.reason).isEqualTo("관리자 지급")
        assertThat(proxy.createdAt).isEqualTo(fixedAt)
        assertThat(proxy.reservation).isNull()
        assertThat(proxy.chargeOrder).isNull()

        entityManager.clear()

        val reloaded =
            creditTransactionRepository
                .findById(transactionId)
                .orElseThrow()

        assertThat(reloaded.amount).isEqualTo(5000)
        assertThat(reloaded.type).isEqualTo(CreditTransactionType.ADMIN_GRANT)
        assertThat(reloaded.balanceAfter).isEqualTo(5000)
    }

    @Test
    @DisplayName("잔액 소멸 시 지급 원장과 소멸 원장의 합계가 최종 잔액과 일치한다")
    fun withdrawalExpirationPreservesLedgerBalance() {
        // given
        val member =
            Member(
                "credit-withdraw-${UUID.randomUUID()}@test.com",
                "탈퇴테스트",
            )

        member.addCredits(10000)

        val savedMember = memberRepository.saveAndFlush(member)
        val memberId = requireNotNull(savedMember.id)
        val grantAt = fixedAt.minusDays(1)

        // 최초 +10,000 지급 원장 생성
        creditTransactionRepository.save(
            CreditTransaction(
                savedMember,
                10000,
                CreditTransactionType.ADMIN_GRANT,
                null,
                10000,
                "초기 지급",
                grantAt,
            ),
        )

        entityManager.flush()
        entityManager.clear()

        // 실제 회원 탈퇴 서비스와 동일하게 잠금 조회
        val lockedMember =
            memberRepository
                .findByIdForUpdate(memberId)
                .orElseThrow()

        // when
        val expiredAmount =
            creditExpirationService.expireForWithdrawal(
                lockedMember,
                fixedAt,
            )

        entityManager.flush()
        entityManager.clear()

        // then
        val reloadedMember =
            memberRepository
                .findById(memberId)
                .orElseThrow()

        assertThat(expiredAmount).isEqualTo(10000)
        assertThat(reloadedMember.balance).isZero()

        val page =
            creditTransactionRepository.findByMember_Id(
                memberId,
                PageRequest.of(0, 10, Sort.by("id").ascending()),
            )

        val transactions = page.content

        assertThat(page.totalElements).isEqualTo(2L)

        val grant = transactions[0]
        val expiration = transactions[1]

        assertThat(grant.type)
            .isEqualTo(CreditTransactionType.ADMIN_GRANT)
        assertThat(grant.amount).isEqualTo(10000)
        assertThat(grant.balanceAfter).isEqualTo(10000)

        assertThat(expiration.type)
            .isEqualTo(CreditTransactionType.WITHDRAWAL_EXPIRATION)
        assertThat(expiration.amount).isEqualTo(-10000)
        assertThat(expiration.balanceAfter).isZero()
        assertThat(expiration.reason)
            .isEqualTo("회원탈퇴로 인한 잔여 크레딧 소멸")
        assertThat(expiration.createdAt).isEqualTo(fixedAt)
        assertThat(expiration.member.id).isEqualTo(memberId)

        val ledgerSum = transactions.sumOf { it.amount }

        // 지급 +10,000과 소멸 -10,000의 합은 최종 잔액 0
        assertThat(ledgerSum).isEqualTo(reloadedMember.balance)
        assertThat(expiration.balanceAfter).isEqualTo(reloadedMember.balance)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("탈퇴 크레딧 소멸은 트랜잭션 없이 호출할 수 없다")
    fun withdrawalExpirationRequiresTransaction() {
        val member =
            Member(
                "credit-no-tx-${UUID.randomUUID()}@test.com",
                "트랜잭션테스트",
            )

        assertThatThrownBy {
            creditExpirationService.expireForWithdrawal(
                member,
                fixedAt,
            )
        }.isInstanceOf(IllegalTransactionStateException::class.java)
    }

    private fun createMember(): Member =
        memberRepository.saveAndFlush(
            Member(
                "credit-step5-${UUID.randomUUID()}@test.com",
                "크레딧테스트",
            ),
        )
}
