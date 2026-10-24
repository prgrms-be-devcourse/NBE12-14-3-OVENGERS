package com.ovengers.slotkey.reservation.scheduler

import com.ovengers.slotkey.access.service.DoorAccessTokenService
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.reservation.service.ReservationHoldExpirationService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime

/** 배치 상태 전이를 예약 한 건당 하나의 트랜잭션으로 처리한다. */
@Component
class ReservationBatchProcessor(
    private val reservationRepository: ReservationRepository,
    private val reservationSlotRepository: ReservationSlotRepository,
    private val reservationStatusHistoryRepository: ReservationStatusHistoryRepository,
    private val doorAccessTokenService: DoorAccessTokenService,
    private val reservationHoldExpirationService: ReservationHoldExpirationService,
) {
    @Transactional
    fun expireHold(
        reservationId: Long?,
        now: LocalDateTime,
    ): Boolean = reservationHoldExpirationService.expireHold(reservationId, now)

    @Transactional
    fun markNoShow(
        reservationId: Long?,
        now: LocalDateTime,
    ): Boolean {
        val requiredReservationId = requireNotNull(reservationId)
        val updated =
            reservationRepository.markNoShowIfNotCheckedIn(
                requiredReservationId,
                now.minus(CHECK_IN_WINDOW),
                ReservationStatus.CONFIRMED,
                ReservationStatus.NO_SHOW,
            )
        if (updated == 0) {
            return false
        }
        reservationSlotRepository.deleteByReservationId(requiredReservationId)
        doorAccessTokenService.revokeByReservation(requiredReservationId, now, "NO_SHOW")
        saveHistory(requiredReservationId, ReservationStatus.CONFIRMED, ReservationStatus.NO_SHOW, "NO_SHOW", now)
        return true
    }

    @Transactional
    fun autoCheckOut(
        reservationId: Long?,
        now: LocalDateTime,
    ): Boolean {
        val requiredReservationId = requireNotNull(reservationId)
        val updated =
            reservationRepository.autoCheckOutIfEnded(
                requiredReservationId,
                now,
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED,
            )
        if (updated == 0) {
            return false
        }
        val completed = requireNotNull(reservationRepository.findByIdOrNull(requiredReservationId))
        val completedEndTime = completed.getEndTime()
        doorAccessTokenService.revokeByReservation(requiredReservationId, completedEndTime, "AUTO_CHECK_OUT")
        saveHistory(
            requiredReservationId,
            ReservationStatus.IN_USE,
            ReservationStatus.COMPLETED,
            "AUTO_CHECK_OUT",
            completedEndTime,
        )
        return true
    }

    private fun saveHistory(
        reservationId: Long,
        from: ReservationStatus,
        to: ReservationStatus,
        reason: String,
        changedAt: LocalDateTime,
    ) {
        reservationStatusHistoryRepository.save(
            ReservationStatusHistory.of(reservationId, null, from, to, reason, changedAt),
        )
    }

    companion object {
        @JvmField
        val CHECK_IN_WINDOW: Duration = Duration.ofMinutes(15)
    }
}
