package com.ovengers.slotkey.reservation.adapter

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.space.service.OccupiedSlotProvider
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** space 도메인의 [OccupiedSlotProvider] 포트에 대한 실제 구현체. */
@Component("ReservationOccupiedSlotAdapter")
class ReservationOccupiedSlotAdapter(
    private val reservationSlotRepository: ReservationSlotRepository,
) : OccupiedSlotProvider {
    override fun getOccupiedSlotStarts(
        spaceId: Long?,
        date: LocalDate?,
        now: LocalDateTime?,
    ): Set<LocalDateTime> =
        reservationSlotRepository
            .findOccupiedSlotStarts(
                requireNotNull(spaceId),
                requireNotNull(date).atStartOfDay(),
                date.plusDays(1).atStartOfDay(),
                requireNotNull(now),
                ReservationStatus.HELD,
                OCCUPIED_STATUSES,
            ).toSet()

    override fun hasOccupiedSlotsOutsideHours(
        spaceId: Long?,
        openTime: LocalTime?,
        closeTime: LocalTime?,
        fromTime: LocalDateTime?,
    ): Boolean =
        reservationSlotRepository
            .findOccupiedSlotStartsEndingAfter(
                requireNotNull(spaceId),
                requireNotNull(fromTime).minusMinutes(30),
                fromTime,
                ReservationStatus.HELD,
                OCCUPIED_STATUSES,
            ).any { slotStart ->
                val requiredOpenTime = requireNotNull(openTime)
                val requiredCloseTime = requireNotNull(closeTime)
                val start = slotStart.toLocalTime()
                val end = start.plusMinutes(30)
                start.isBefore(requiredOpenTime) ||
                    end.isAfter(requiredCloseTime) ||
                    (end == LocalTime.MIDNIGHT && requiredCloseTime != LocalTime.MIDNIGHT)
            }

    override fun getOccupiedSpaceIds(
        startInclusive: LocalDateTime?,
        endExclusive: LocalDateTime?,
        now: LocalDateTime?,
    ): Set<Long> =
        reservationSlotRepository
            .findOccupiedSpaceIds(
                requireNotNull(startInclusive),
                requireNotNull(endExclusive),
                requireNotNull(now),
                ReservationStatus.HELD,
                OCCUPIED_STATUSES,
            ).toSet()

    companion object {
        private val OCCUPIED_STATUSES =
            listOf(ReservationStatus.CONFIRMED, ReservationStatus.IN_USE, ReservationStatus.COMPLETED)
    }
}
