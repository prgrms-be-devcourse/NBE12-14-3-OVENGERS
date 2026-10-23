package com.ovengers.slotkey.space.dto.response

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.LocalDate

@JvmRecord
data class SpaceSlotAvailabilityResponse(
    val spaceId: Long,
    @field:JsonFormat(pattern = "yyyy-MM-dd") @get:JsonFormat(pattern = "yyyy-MM-dd") val date: LocalDate,
    val slots: List<SlotResponse>,
) {
    companion object {
        @JvmStatic
        fun of(
            spaceId: Long,
            date: LocalDate,
            slots: List<SlotResponse>,
        ): SpaceSlotAvailabilityResponse = SpaceSlotAvailabilityResponse(spaceId, date, slots)
    }
}
