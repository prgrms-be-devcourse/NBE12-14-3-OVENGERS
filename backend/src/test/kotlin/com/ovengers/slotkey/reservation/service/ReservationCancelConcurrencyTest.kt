package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 같은 예약에 취소가 겹쳐도 환불이 한 번만 일어나는지 실제 DB로 검증한다.
 * 사용자 중복 요청 및 사용자·관리자 동시 취소를 확인한다.
 */
class ReservationCancelConcurrencyTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var reservationCancelService: ReservationCancelService

    @Autowired
    private lateinit var adminReservationService: AdminReservationService

    private var memberId: Long = 0L
    private var adminId: Long = 0L
    private var spaceId: Long = 0L

    @BeforeEach
    fun setUp() {
        memberId = createMember(INITIAL_BALANCE)
        adminId = createMember(0)
        spaceId = createSpace()
    }

    @Test
    @DisplayName("같은 사용자가 같은 예약을 동시에 여러 번 취소해도 정확히 1건만 성공하고 환불은 1회만 일어난다")
    fun cancel_sameUserConcurrently_refundsOnce() {
        val reservationId =
            createReservation(
                memberId,
                spaceId,
                ReservationStatus.CONFIRMED,
                tomorrowAt(14, 0),
                tomorrowAt(15, 0),
            )

        val tasks =
            (0 until THREADS).map {
                Runnable { reservationCancelService.cancel(memberId, reservationId) }
            }

        val results = runConcurrently(tasks)

        assertThat(successCount(results)).isEqualTo(1)
        assertThat(failures(results))
            .hasSize(THREADS - 1)
            .containsOnly(ErrorCode.RESERVATION_STATE_CONFLICT)

        assertRefundedExactlyOnce(reservationId, INITIAL_BALANCE)
    }

    @Test
    @DisplayName("사용자 본인 취소와 관리자 강제 취소가 동시에 처리돼도 정확히 한쪽만 성공하고 환불은 1회만 일어난다")
    fun cancel_userAndAdminConcurrently_refundsOnce() {
        for (round in 0 until ROUNDS) {
            val balanceBefore = balanceOf(memberId)
            val reservationId =
                createReservation(
                    memberId,
                    spaceId,
                    ReservationStatus.CONFIRMED,
                    tomorrowAt(9 + round * 2, 0),
                    tomorrowAt(10 + round * 2, 0),
                )

            val tasks =
                listOf(
                    Runnable {
                        reservationCancelService.cancel(memberId, reservationId)
                    },
                    Runnable {
                        adminReservationService.forceCancel(
                            reservationId,
                            "동시 취소 경합 검증",
                            adminId,
                        )
                    },
                )

            val results = runConcurrently(tasks)

            assertThat(successCount(results))
                .`as`("round %d", round)
                .isEqualTo(1)

            assertThat(failures(results))
                .`as`("round %d", round)
                .containsExactly(ErrorCode.RESERVATION_STATE_CONFLICT)

            assertRefundedExactlyOnce(reservationId, balanceBefore)
        }
    }

    @Test
    @DisplayName("부분 환불을 동시에 요청해도 환불과 위약금 원장이 각각 한 번만 기록된다")
    fun cancel_partialRefundConcurrently_recordsRefundAndPenaltyOnce() {
        val startTime = now().plusMinutes(30)

        val reservationId =
            createReservation(
                memberId,
                spaceId,
                ReservationStatus.CONFIRMED,
                startTime,
                startTime.plusHours(1),
            )

        val tasks =
            listOf(
                Runnable { reservationCancelService.cancel(memberId, reservationId) },
                Runnable { reservationCancelService.cancel(memberId, reservationId) },
            )

        val results = runConcurrently(tasks)

        assertThat(successCount(results)).isEqualTo(1)
        assertThat(failures(results)).containsExactly(ErrorCode.RESERVATION_STATE_CONFLICT)
        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED")
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE + TOTAL_AMOUNT / 2)
        assertThat(countLedger(reservationId, "REFUND")).isEqualTo(1)
        assertThat(sumLedger(reservationId, "REFUND")).isEqualTo(TOTAL_AMOUNT)
        assertThat(countLedger(reservationId, "PENALTY")).isEqualTo(1)
        assertThat(sumLedger(reservationId, "PENALTY")).isEqualTo(-TOTAL_AMOUNT / 2)

        val refundBalanceAfter =
            jdbcTemplate.queryForObject(
                """
                SELECT balance_after
                FROM credit_transaction
                WHERE reservation_id = ? AND type = 'REFUND'
                """.trimIndent(),
                Int::class.javaObjectType,
                reservationId,
            )

        val penaltyBalanceAfter =
            jdbcTemplate.queryForObject(
                """
                SELECT balance_after
                FROM credit_transaction
                WHERE reservation_id = ? AND type = 'PENALTY'
                """.trimIndent(),
                Int::class.javaObjectType,
                reservationId,
            )

        assertThat(refundBalanceAfter).isEqualTo(INITIAL_BALANCE + TOTAL_AMOUNT)
        assertThat(penaltyBalanceAfter).isEqualTo(INITIAL_BALANCE + TOTAL_AMOUNT / 2)
        assertThat(countSlots(reservationId)).isZero()
        assertThat(countHistory(reservationId, "CANCELLED")).isEqualTo(1)
    }

    private fun assertRefundedExactlyOnce(
        reservationId: Long,
        balanceBefore: Int,
    ) {
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE JSON_EXTRACT(payload, '$.reservationId') = ?",
                Int::class.javaObjectType,
                reservationId,
            ),
        ).isEqualTo(1)

        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED")
        assertThat(balanceOf(memberId)).isEqualTo(balanceBefore + TOTAL_AMOUNT)
        assertThat(countLedger(reservationId, "REFUND")).isEqualTo(1)
        assertThat(sumLedger(reservationId, "REFUND")).isEqualTo(TOTAL_AMOUNT)
        assertThat(countLedger(reservationId, "PENALTY")).isZero()
        assertThat(countSlots(reservationId)).isZero()
        assertThat(countHistory(reservationId, "CANCELLED")).isEqualTo(1)
    }

    private companion object {
        const val INITIAL_BALANCE = 3_000
        const val TOTAL_AMOUNT = 10_000
        const val THREADS = 5
        const val ROUNDS = 5
    }
}
