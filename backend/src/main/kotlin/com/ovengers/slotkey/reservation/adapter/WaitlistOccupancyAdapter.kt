package com.ovengers.slotkey.reservation.adapter

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.waitlist.service.ReservationOccupancyProvider
import com.ovengers.slotkey.waitlist.service.ReservationRangeProvider
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/** waitlist 도메인의 점유 여부·예약 구간 포트 구현체. */
@Component
class WaitlistOccupancyAdapter(
    private val reservationSlotRepository: ReservationSlotRepository,
    private val reservationRepository: ReservationRepository,
) : ReservationOccupancyProvider,
    ReservationRangeProvider {
    override fun hasOccupiedSlot(
        spaceId: Long?,
        startInclusive: LocalDateTime?,
        endExclusive: LocalDateTime?,
        now: LocalDateTime?,
    ): Boolean =
        reservationSlotRepository
            .findOccupiedSlotStarts(
                requireNotNull(spaceId),
                requireNotNull(startInclusive),
                requireNotNull(endExclusive),
                requireNotNull(now),
                ReservationStatus.HELD,
                SLOT_OCCUPIED_STATUSES,
            ).isNotEmpty()

    override fun hasOwnOccupyingReservationOverlapping(
        memberId: Long?,
        spaceId: Long?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        now: LocalDateTime?,
    ): Boolean =
        reservationRepository.existsOccupyingByMemberAndSpace(
            requireNotNull(memberId),
            requireNotNull(spaceId),
            requireNotNull(startTime),
            requireNotNull(endTime),
            requireNotNull(now),
            ReservationStatus.HELD,
            OWN_OCCUPYING_STATUSES,
        )

    override fun findRange(reservationId: Long?): ReservationRangeProvider.ReservationRange? =
        reservationRepository
            .findById(requireNotNull(reservationId))
            .map { reservation ->
                ReservationRangeProvider.ReservationRange(
                    reservation.spaceId,
                    reservation.startTime,
                    reservation.endTime,
                )
            }.orElse(null)

    companion object {
        private val SLOT_OCCUPIED_STATUSES =
            listOf(ReservationStatus.CONFIRMED, ReservationStatus.IN_USE, ReservationStatus.COMPLETED)
        private val OWN_OCCUPYING_STATUSES = listOf(ReservationStatus.CONFIRMED, ReservationStatus.IN_USE)
    }
}
