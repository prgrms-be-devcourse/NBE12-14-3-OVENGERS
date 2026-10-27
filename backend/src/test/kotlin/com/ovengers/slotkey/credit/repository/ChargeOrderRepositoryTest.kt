package com.ovengers.slotkey.credit.repository

import com.ovengers.slotkey.credit.entity.ChargeOrder
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Transactional
class ChargeOrderRepositoryTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var chargeOrderRepository: ChargeOrderRepository

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Test
    @DisplayName("orderId는 PK와 별도 컬럼이며 DB UNIQUE 제약으로 중복을 거절한다")
    fun save_duplicateOrderId_violatesUniqueConstraint() {
        val member =
            memberRepository.save(
                Member("charge-order@test.com", "충전회원"),
            )
        val orderId = "chg_550e8400-e29b-41d4-a716-446655440000"
        val now = LocalDateTime.of(2026, 10, 13, 9, 0)

        val first =
            chargeOrderRepository.saveAndFlush(
                ChargeOrder.createPending(member.id, orderId, 1_000L, now),
            )

        assertThat(first.id).isNotNull()
        assertThat(first.orderId).isEqualTo(orderId)
        assertThat(first.id.toString()).isNotEqualTo(orderId)

        assertThatThrownBy {
            chargeOrderRepository.saveAndFlush(
                ChargeOrder.createPending(member.id, orderId, 1_100L, now),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    @DisplayName("CONFIRMING 전이는 최초 복구 시각을 전이 1분 뒤로 저장한다")
    fun startConfirming_schedulesFirstRecoveryAfterOneMinute() {
        val member =
            memberRepository.save(
                Member("recovery-schedule@test.com", "복구회원"),
            )
        val confirmingAt = LocalDateTime.of(2026, 10, 14, 12, 0)
        val order =
            chargeOrderRepository.saveAndFlush(
                ChargeOrder.createPending(
                    member.id,
                    "chg_650e8400-e29b-41d4-a716-446655440000",
                    1_000L,
                    confirmingAt.minusMinutes(5),
                ),
            )

        val updated =
            chargeOrderRepository.startConfirming(
                order.id,
                ChargeOrderStatus.PENDING,
                ChargeOrderStatus.CONFIRMING,
                "payment-key",
                "client-key",
                "pg-key",
                confirmingAt,
                confirmingAt.plusMinutes(1),
            )

        val confirming = chargeOrderRepository.findByOrderId(order.orderId)!!
        assertThat(updated).isEqualTo(1)
        assertThat(confirming.status).isEqualTo(ChargeOrderStatus.CONFIRMING)
        assertThat(confirming.updatedAt).isEqualTo(confirmingAt)
        assertThat(confirming.recoveryNextAttemptAt)
            .isEqualTo(confirmingAt.plusMinutes(1))
    }

    @Test
    @DisplayName("복구 후보는 1분 경계를 포함하고 24시간 경계를 제외해 다음 시도 시각 순으로 조회한다")
    fun findRecoveryCandidateIds_appliesTimeWindowAndStableOrder() {
        val member =
            memberRepository.save(
                Member("recovery-candidates@test.com", "후보회원"),
            )
        val now = LocalDateTime.of(2026, 10, 14, 12, 0)
        val missingRecoverySchedule = confirmingOrder(member, 0, now.minusMinutes(2))
        chargeOrderRepository
            .findById(missingRecoverySchedule.id!!)
            .orElseThrow()
            .scheduleNextRecovery(null)
        chargeOrderRepository.flush()
        val olderEligible = confirmingOrder(member, 1, now.minusMinutes(2))
        val exactOneMinute = confirmingOrder(member, 2, now.minusMinutes(1))
        confirmingOrder(member, 3, now.minusSeconds(59))
        val exactTwentyFourHours = confirmingOrder(member, 4, now.minusHours(24))

        val candidates =
            chargeOrderRepository.findRecoveryCandidateIds(
                ChargeOrderStatus.CONFIRMING,
                now,
                now.minusHours(24),
                PageRequest.of(0, 50),
            )
        val exhausted =
            chargeOrderRepository.findRecoveryExhaustedCandidateIds(
                ChargeOrderStatus.CONFIRMING,
                now.minusHours(24),
                PageRequest.of(0, 50),
            )

        assertThat(candidates).containsExactly(
            missingRecoverySchedule.id,
            olderEligible.id,
            exactOneMinute.id,
        )
        assertThat(exhausted).containsExactly(exactTwentyFourHours.id)
    }

    @Test
    @DisplayName("첫 50건의 다음 시각을 미루면 51번째 CONFIRMING 주문이 다음 조회에 포함된다")
    fun findRecoveryCandidateIds_rescheduledFirstBatchDoesNotStarveNextOrder() {
        val member =
            memberRepository.save(
                Member("recovery-fairness@test.com", "공정성회원"),
            )
        val now = LocalDateTime.of(2026, 10, 14, 12, 0)
        for (sequence in 1..51) {
            confirmingOrder(member, 100 + sequence, now.minusMinutes(2))
        }
        val firstFifty = PageRequest.of(0, 50)

        val firstBatch =
            chargeOrderRepository.findRecoveryCandidateIds(
                ChargeOrderStatus.CONFIRMING,
                now,
                now.minusHours(24),
                firstFifty,
            )
        firstBatch.forEach { id ->
            chargeOrderRepository
                .findById(id)
                .orElseThrow()
                .scheduleNextRecovery(now.plusMinutes(1))
        }

        val nextBatch =
            chargeOrderRepository.findRecoveryCandidateIds(
                ChargeOrderStatus.CONFIRMING,
                now,
                now.minusHours(24),
                firstFifty,
            )

        assertThat(firstBatch).hasSize(50)
        assertThat(nextBatch).hasSize(1)
        assertThat(firstBatch).doesNotContain(nextBatch.get(0))
    }

    private fun confirmingOrder(
        member: Member,
        sequence: Int,
        confirmingAt: LocalDateTime,
    ): ChargeOrder {
        val order =
            chargeOrderRepository.saveAndFlush(
                ChargeOrder.createPending(
                    member.id,
                    "chg_750e8400-e29b-41d4-a716-44665544000" + sequence,
                    1_000L,
                    confirmingAt.minusMinutes(5),
                ),
            )
        chargeOrderRepository.startConfirming(
            order.id,
            ChargeOrderStatus.PENDING,
            ChargeOrderStatus.CONFIRMING,
            "payment-key-" + sequence,
            "client-key-" + sequence,
            "pg-key-" + sequence,
            confirmingAt,
            confirmingAt.plusMinutes(1),
        )
        return order
    }

    @Test
    @DisplayName("내 충전 내역은 본인의 PAID·FAILED만 최신순으로 반환하고 PENDING과 타인 주문은 제외한다")
    fun findHistory_returnsOwnVisibleOrdersNewestFirst() {
        val me = memberRepository.save(Member("history-me@test.com", "내역회원"))
        val other = memberRepository.save(Member("history-other@test.com", "타인회원"))
        val now = LocalDateTime.of(2026, 10, 16, 9, 0)

        val paid = ChargeOrder.createPending(me.id, "chg_hist_paid", 5_000L, now)
        paid.markPaid(now.plusMinutes(1))
        chargeOrderRepository.save(paid)
        chargeOrderRepository.save(ChargeOrder.createPending(me.id, "chg_hist_pending", 1_000L, now.plusMinutes(2)))
        chargeOrderRepository.save(ChargeOrder.createPending(other.id, "chg_hist_other", 2_000L, now.plusMinutes(3)))
        val newer = ChargeOrder.createPending(me.id, "chg_hist_paid2", 3_000L, now.plusMinutes(4))
        newer.markPaid(now.plusMinutes(5))
        chargeOrderRepository.saveAndFlush(newer)

        val page =
            chargeOrderRepository.findByMemberIdAndStatusInOrderByIdDesc(
                me.id,
                setOf(ChargeOrderStatus.PAID, ChargeOrderStatus.CONFIRMING, ChargeOrderStatus.FAILED),
                PageRequest.of(0, 10),
            )

        assertThat(page.content)
            .extracting<String> { it.orderId }
            .containsExactly("chg_hist_paid2", "chg_hist_paid")
        assertThat(page.totalElements).isEqualTo(2)
    }
}
