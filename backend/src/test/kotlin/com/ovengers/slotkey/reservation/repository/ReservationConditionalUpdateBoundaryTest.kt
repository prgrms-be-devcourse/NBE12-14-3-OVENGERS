package com.ovengers.slotkey.reservation.repository

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

class ReservationConditionalUpdateBoundaryTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private var memberId: Long = 0
    private var spaceId: Long = 0

    @BeforeEach
    fun setUp() {
        memberId = createMember(100_000)
        spaceId = createSpace()
    }

    @Test
    fun `HELD cancellation changes status and records the cancellation time`() {
        val start = LocalDateTime.of(2026, 11, 1, 10, 0)
        val cancelledAt = start.minusHours(1)
        val reservationId = createReservation(memberId, spaceId, ReservationStatus.HELD, start, start.plusMinutes(30))

        val updated =
            inTransaction {
                reservationRepository.cancelIfHeld(
                    reservationId,
                    cancelledAt,
                    ReservationStatus.HELD,
                    ReservationStatus.CANCELLED,
                )
            }

        assertThat(updated).isEqualTo(1)
        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED")
        assertThat(cancelledAtOf(reservationId)).isEqualTo(cancelledAt)
    }

    @Test
    fun `HELD cancellation does not change a CONFIRMED reservation`() {
        val start = LocalDateTime.of(2026, 11, 1, 10, 0)
        val reservationId =
            createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, start, start.plusMinutes(30))

        val updated =
            inTransaction {
                reservationRepository.cancelIfHeld(
                    reservationId,
                    start.minusHours(1),
                    ReservationStatus.HELD,
                    ReservationStatus.CANCELLED,
                )
            }

        assertThat(updated).isZero()
        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED")
    }

    @Test
    fun `no-show does not transition at the exact check-in deadline`() {
        val start = LocalDateTime.of(2026, 11, 1, 10, 0)
        val reservationId =
            createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, start, start.plusMinutes(30))

        val updated = markNoShow(reservationId, start)

        assertThat(updated).isZero()
        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED")
    }

    @Test
    fun `no-show transitions one second after the check-in deadline`() {
        val start = LocalDateTime.of(2026, 11, 1, 10, 0)
        val reservationId =
            createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, start, start.plusMinutes(30))

        val updated = markNoShow(reservationId, start.plusSeconds(1))

        assertThat(updated).isEqualTo(1)
        assertThat(statusOf(reservationId)).isEqualTo("NO_SHOW")
    }

    @Test
    fun `no-show does not change a HELD reservation after the deadline`() {
        val start = LocalDateTime.of(2026, 11, 1, 10, 0)
        val reservationId =
            createReservation(memberId, spaceId, ReservationStatus.HELD, start, start.plusMinutes(30))

        val updated = markNoShow(reservationId, start.plusSeconds(1))

        assertThat(updated).isZero()
        assertThat(statusOf(reservationId)).isEqualTo("HELD")
    }

    @Test
    fun `automatic checkout transitions at the exact end time and records the end time`() {
        val start = LocalDateTime.of(2026, 11, 1, 10, 0)
        val end = start.plusMinutes(30)
        val reservationId = createInUseReservation(start, end)

        val updated = autoCheckOut(reservationId, end)

        assertThat(updated).isEqualTo(1)
        assertThat(statusOf(reservationId)).isEqualTo("COMPLETED")
        assertThat(checkedOutAtOf(reservationId)).isEqualTo(end)
    }

    @Test
    fun `automatic checkout does not transition one second before the end time`() {
        val start = LocalDateTime.of(2026, 11, 1, 10, 0)
        val end = start.plusMinutes(30)
        val reservationId = createInUseReservation(start, end)

        val updated = autoCheckOut(reservationId, end.minusSeconds(1))

        assertThat(updated).isZero()
        assertThat(statusOf(reservationId)).isEqualTo("IN_USE")
    }

    @Test
    fun `automatic checkout does not change a CONFIRMED reservation after the end time`() {
        val start = LocalDateTime.of(2026, 11, 1, 10, 0)
        val end = start.plusMinutes(30)
        val reservationId = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, start, end)

        val updated = autoCheckOut(reservationId, end.plusSeconds(1))

        assertThat(updated).isZero()
        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED")
    }

    private fun markNoShow(
        reservationId: Long,
        threshold: LocalDateTime,
    ): Int =
        inTransaction {
            reservationRepository.markNoShowIfNotCheckedIn(
                reservationId,
                threshold,
                ReservationStatus.CONFIRMED,
                ReservationStatus.NO_SHOW,
            )
        }

    private fun autoCheckOut(
        reservationId: Long,
        now: LocalDateTime,
    ): Int =
        inTransaction {
            reservationRepository.autoCheckOutIfEnded(
                reservationId,
                now,
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED,
            )
        }

    private fun createInUseReservation(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Long {
        val reservationId = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, start, end)
        val updated =
            inTransaction {
                reservationRepository.checkInIfConfirmed(
                    reservationId,
                    start,
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.IN_USE,
                )
            }
        assertThat(updated).isEqualTo(1)
        return reservationId
    }

    private fun inTransaction(block: () -> Int): Int = requireNotNull(transactionTemplate.execute { block() })

    private fun cancelledAtOf(reservationId: Long): LocalDateTime? =
        jdbcTemplate.queryForObject(
            "SELECT cancelled_at FROM reservation WHERE id = ?",
            LocalDateTime::class.java,
            reservationId,
        )

    private fun checkedOutAtOf(reservationId: Long): LocalDateTime? =
        jdbcTemplate.queryForObject(
            "SELECT checked_out_at FROM reservation WHERE id = ?",
            LocalDateTime::class.java,
            reservationId,
        )
}
