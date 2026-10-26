package com.ovengers.slotkey.space.service

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Component
@ConditionalOnMissingBean(name = ["ReservationOccupiedSlotAdapter"])
class DefaultOccupiedSlotProvider : OccupiedSlotProvider {
    override fun getOccupiedSlotStarts(
        spaceId: Long?,
        date: LocalDate?,
        now: LocalDateTime?,
    ): Set<LocalDateTime> = emptySet()

    override fun hasOccupiedSlotsOutsideHours(
        spaceId: Long?,
        openTime: LocalTime?,
        closeTime: LocalTime?,
        fromTime: LocalDateTime?,
    ): Boolean = false

    override fun getOccupiedSpaceIds(
        startInclusive: LocalDateTime?,
        endExclusive: LocalDateTime?,
        now: LocalDateTime?,
    ): Set<Long> = emptySet()
}
