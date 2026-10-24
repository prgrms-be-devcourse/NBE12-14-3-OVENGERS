package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.outbox.SlotReleasedRecorder
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/** HELD 만료 전이, 슬롯 정리, 상태 이력과 SlotReleased 기록을 원자적으로 처리한다. */
@Service
class ReservationHoldExpirationService(
    private val reservationRepository: ReservationRepository,
    private val reservationSlotRepository: ReservationSlotRepository,
    private val reservationStatusHistoryRepository: ReservationStatusHistoryRepository,
    private val slotReleasedRecorder: SlotReleasedRecorder,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun expireHold(
        reservationId: Long?,
        now: LocalDateTime,
    ): Boolean {
        val requiredReservationId = requireNotNull(reservationId)
        val ids = listOf(requiredReservationId)
        val updated =
            reservationRepository.expireHeldReservations(
                ids,
                now,
                ReservationStatus.HELD,
                ReservationStatus.EXPIRED,
            )
        reservationSlotRepository.deleteSlotsOfExpiredReservations(ids, ReservationStatus.EXPIRED)
        if (updated == 0) {
            return false
        }

        reservationStatusHistoryRepository.save(
            ReservationStatusHistory.of(
                requiredReservationId,
                null,
                ReservationStatus.HELD,
                ReservationStatus.EXPIRED,
                "HOLD_EXPIRED",
                now,
            ),
        )
        val spaceId = requireNotNull(reservationRepository.findByIdOrNull(requiredReservationId)).spaceId
        slotReleasedRecorder.record(requiredReservationId, spaceId, SlotReleasedRecorder.Reason.HOLD_EXPIRED)
        return true
    }
}
