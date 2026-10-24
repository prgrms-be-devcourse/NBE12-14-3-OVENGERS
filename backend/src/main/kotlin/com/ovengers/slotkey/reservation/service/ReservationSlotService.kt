package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.entity.ReservationSlot
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

private const val SLOT_MINUTES = 30L

/** reservation_slot의 UNIQUE 제약을 슬롯 점유의 유일한 판정으로 사용한다. */
@Service
class ReservationSlotService(
    private val reservationSlotRepository: ReservationSlotRepository,
    private val reservationHoldExpirationService: ReservationHoldExpirationService,
    private val clock: Clock,
) {
    fun buildSlotStarts(
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<LocalDateTime> =
        generateSequence(start) { cursor -> cursor.plusMinutes(SLOT_MINUTES) }
            .takeWhile { cursor -> cursor.isBefore(end) }
            .toList()

    @Transactional
    fun secureSlots(
        reservationId: Long?,
        spaceId: Long?,
        slotStarts: List<LocalDateTime>,
    ) {
        val requiredReservationId = requireNotNull(reservationId)
        val requiredSpaceId = requireNotNull(spaceId)
        cleanupExpiredHolds(requiredSpaceId, slotStarts)
        val slots = slotStarts.map { start -> ReservationSlot.of(requiredReservationId, requiredSpaceId, start) }
        try {
            reservationSlotRepository.saveAll(slots)
            reservationSlotRepository.flush()
        } catch (_: DataIntegrityViolationException) {
            throw BusinessException(ErrorCode.RESERVATION_SLOT_CONFLICT)
        }
    }

    private fun cleanupExpiredHolds(
        spaceId: Long,
        slotStarts: List<LocalDateTime>,
    ) {
        val now = LocalDateTime.now(clock)
        reservationSlotRepository
            .findExpiredReservationIdsBySpaceIdAndSlotStartIn(
                spaceId,
                slotStarts,
                now,
                ReservationStatus.HELD,
                ReservationStatus.EXPIRED,
            ).forEach { candidateId -> reservationHoldExpirationService.expireHold(candidateId, now) }
    }
}
