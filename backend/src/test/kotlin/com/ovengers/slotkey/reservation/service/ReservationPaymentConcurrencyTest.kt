package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate

/**
 * 결제 확인이 반복·동시 요청에서도 크레딧을 정확히 한 번만 차감하는지 실제 DB로 검증한다.
 * 조건부 UPDATE와 실패 시 롤백의 기존 검증 조건을 유지한다.
 */
class ReservationPaymentConcurrencyTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var reservationHoldService: ReservationHoldService

    @Autowired
    private lateinit var reservationPaymentConfirmService: ReservationPaymentConfirmService

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private var memberId: Long = 0L
    private lateinit var hold: ReservationResponse

    @BeforeEach
    fun setUp() {
        memberId = createMember(INITIAL_BALANCE)
        val spaceId = createSpace()
        hold = reservationHoldService.createHold(memberId, spaceId, tomorrowAt(14, 0), tomorrowAt(15, 0))
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 결제를 3번 순차 요청해도 크레딧은 1회만 차감되고 매번 같은 결과를 돌려받는다")
    fun pay_sameKeyRepeated_chargesOnce() {
        val first = pay("key-1")
        val second = pay("key-1")
        val third = pay("key-1")

        assertThat(listOf(first, second, third).map { it.status }).containsOnly("CONFIRMED")
        assertThat(listOf(second, third).map { it.reservationId }).containsOnly(first.reservationId)
        assertChargedExactlyOnce()
        assertThat(countIdempotencyKeys(memberId)).isEqualTo(1)
    }

    @Test
    @DisplayName("같은 예약에 서로 다른 Idempotency-Key로 결제가 동시에 들어와도 정확히 1건만 성공하고 크레딧은 1회만 차감된다")
    fun pay_differentKeysConcurrently_chargesOnce() {
        val tasks = (0 until THREADS).map { index -> Runnable { pay("key-$index") } }

        val results = runConcurrently(tasks)

        assertThat(successCount(results)).isEqualTo(1)
        assertThat(failures(results))
            .hasSize(THREADS - 1)
            .containsOnly(ErrorCode.RESERVATION_STATE_CONFLICT)
        assertChargedExactlyOnce()
        assertThat(countHistory(hold.reservationId, "CONFIRMED")).isEqualTo(1)
    }

    @Test
    @DisplayName("서로 다른 예약을 동시에 결제해도 회원 잔액을 초과하여 차감하지 않는다")
    fun pay_differentReservationsConcurrently_preventsOverdraft() {
        val anotherSpaceId = createSpace()
        val anotherHold =
            reservationHoldService.createHold(
                memberId,
                anotherSpaceId,
                tomorrowAt(16, 0),
                tomorrowAt(17, 0),
            )

        val chargeAmount = hold.totalAmount
        val balanceBefore = chargeAmount + PRICE_PER_SLOT

        assertThat(anotherHold.totalAmount).isEqualTo(chargeAmount)
        setBalance(memberId, balanceBefore)

        val tasks =
            listOf(
                Runnable {
                    reservationPaymentConfirmService.confirm(
                        memberId,
                        hold.reservationId,
                        requireNotNull(hold.spaceVersion),
                        "overdraft-key-1",
                    )
                },
                Runnable {
                    reservationPaymentConfirmService.confirm(
                        memberId,
                        anotherHold.reservationId,
                        requireNotNull(anotherHold.spaceVersion),
                        "overdraft-key-2",
                    )
                },
            )

        val results = runConcurrently(tasks)

        assertThat(successCount(results)).isEqualTo(1)
        assertThat(failures(results)).containsExactly(ErrorCode.INSUFFICIENT_BALANCE)
        assertThat(balanceOf(memberId)).isEqualTo(balanceBefore - chargeAmount)
        assertThat(listOf(statusOf(hold.reservationId), statusOf(anotherHold.reservationId)))
            .containsExactlyInAnyOrder("CONFIRMED", "HELD")

        for (target in listOf(hold, anotherHold)) {
            val reservationId = target.reservationId

            if ("CONFIRMED" == statusOf(reservationId)) {
                assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isEqualTo(1)
                assertThat(sumLedger(reservationId, "RESERVATION_CHARGE")).isEqualTo(-chargeAmount)
                assertThat(countHistory(reservationId, "CONFIRMED")).isEqualTo(1)
            } else {
                assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isZero()
                assertThat(countHistory(reservationId, "CONFIRMED")).isZero()
            }
        }

        assertThat(countIdempotencyKeys(memberId)).isEqualTo(1)
    }

    @Test
    @DisplayName("같은 Idempotency-Key라도 서로 다른 예약이면 각각 결제된다")
    fun pay_sameKeyForDifferentReservations_chargesBoth() {
        val anotherSpaceId = createSpace()
        val anotherHold =
            reservationHoldService.createHold(
                memberId,
                anotherSpaceId,
                tomorrowAt(16, 0),
                tomorrowAt(17, 0),
            )
        val totalAmount = hold.totalAmount + anotherHold.totalAmount

        val first =
            reservationPaymentConfirmService.confirm(
                memberId,
                hold.reservationId,
                requireNotNull(hold.spaceVersion),
                "same-key-different-reservation",
            )
        val second =
            reservationPaymentConfirmService.confirm(
                memberId,
                anotherHold.reservationId,
                requireNotNull(anotherHold.spaceVersion),
                "same-key-different-reservation",
            )

        assertThat(first.status).isEqualTo("CONFIRMED")
        assertThat(second.status).isEqualTo("CONFIRMED")
        assertThat(first.reservationId).isNotEqualTo(second.reservationId)
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE - totalAmount)
        assertThat(countLedger(hold.reservationId, "RESERVATION_CHARGE")).isEqualTo(1)
        assertThat(countLedger(anotherHold.reservationId, "RESERVATION_CHARGE")).isEqualTo(1)
        assertThat(sumLedger(hold.reservationId, "RESERVATION_CHARGE")).isEqualTo(-hold.totalAmount)
        assertThat(sumLedger(anotherHold.reservationId, "RESERVATION_CHARGE")).isEqualTo(-anotherHold.totalAmount)
        assertThat(countIdempotencyKeys(memberId)).isEqualTo(2)
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 결제가 동시에 들어와도 크레딧은 1회만 차감되고, 이후 같은 키 재요청은 최초 응답을 그대로 돌려준다")
    fun pay_sameKeyConcurrently_chargesOnceAndReplays() {
        val tasks = (0 until THREADS).map { Runnable { pay("shared-key") } }

        val results = runConcurrently(tasks)

        assertThat(successCount(results)).isGreaterThanOrEqualTo(1)
        assertThat(failures(results)).isSubsetOf(ErrorCode.RESERVATION_STATE_CONFLICT)
        assertChargedExactlyOnce()

        val replayed = pay("shared-key")

        assertThat(replayed.status).isEqualTo("CONFIRMED")
        assertChargedExactlyOnce()
        assertThat(countIdempotencyKeys(memberId)).isEqualTo(1)
    }

    @Test
    @DisplayName("잔액 부족으로 결제가 실패하면 Idempotency-Key가 소진되지 않아, 충전 후 같은 키로 재시도하면 성공한다")
    fun pay_insufficientBalance_doesNotConsumeIdempotencyKey() {
        setBalance(memberId, 0)

        assertThatThrownBy { pay("retry-key") }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_BALANCE)

        assertThat(statusOf(hold.reservationId)).isEqualTo("HELD")
        assertThat(countIdempotencyKeys(memberId)).isZero()
        assertThat(countLedger(hold.reservationId, "RESERVATION_CHARGE")).isZero()

        setBalance(memberId, INITIAL_BALANCE)
        val retried = pay("retry-key")

        assertThat(retried.status).isEqualTo("CONFIRMED")
        assertChargedExactlyOnce()
        assertThat(countIdempotencyKeys(memberId)).isEqualTo(1)
    }

    @Test
    @DisplayName("결제 확정 조건부 UPDATE는 hold_expires_at 정각에는 확정하지 않고 1초 전까지만 확정한다(만료 쿼리의 <= 와 경계가 맞물린다)")
    fun confirmIfHeldAndNotExpired_atExactExpiry_doesNotConfirm() {
        val expiresAt =
            requireNotNull(
                reservationRepository.findById(hold.reservationId).orElseThrow().holdExpiresAt,
            )

        val atExpiry =
            requireNotNull(
                transactionTemplate.execute {
                    reservationRepository.confirmIfHeldAndNotExpired(
                        hold.reservationId,
                        expiresAt,
                        ReservationStatus.HELD,
                        ReservationStatus.CONFIRMED,
                    )
                },
            )
        assertThat(atExpiry).isZero()
        assertThat(statusOf(hold.reservationId)).isEqualTo("HELD")

        val oneSecondBefore =
            requireNotNull(
                transactionTemplate.execute {
                    reservationRepository.confirmIfHeldAndNotExpired(
                        hold.reservationId,
                        expiresAt.minusSeconds(1),
                        ReservationStatus.HELD,
                        ReservationStatus.CONFIRMED,
                    )
                },
            )
        assertThat(oneSecondBefore).isEqualTo(1)
        assertThat(statusOf(hold.reservationId)).isEqualTo("CONFIRMED")
    }

    private fun pay(idempotencyKey: String): ReservationResponse =
        reservationPaymentConfirmService.confirm(
            memberId,
            hold.reservationId,
            requireNotNull(hold.spaceVersion),
            idempotencyKey,
        )

    private fun assertChargedExactlyOnce() {
        assertThat(statusOf(hold.reservationId)).isEqualTo("CONFIRMED")
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE - hold.totalAmount)
        assertThat(countLedger(hold.reservationId, "RESERVATION_CHARGE")).isEqualTo(1)
        assertThat(sumLedger(hold.reservationId, "RESERVATION_CHARGE")).isEqualTo(-hold.totalAmount)
    }

    private companion object {
        const val INITIAL_BALANCE = 100_000
        const val THREADS = 5
    }
}
