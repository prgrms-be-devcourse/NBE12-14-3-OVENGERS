package com.ovengers.slotkey.space.dto.response

import com.fasterxml.jackson.annotation.JsonFormat
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import java.time.LocalTime

@JvmRecord
data class SpaceResponse(
    val id: Long,
    val name: String,
    val location: String,
    val capacity: Int,
    val pricePerSlot: Long,
    val imagePath: String?,
    @field:JsonFormat(pattern = "HH:mm") @get:JsonFormat(pattern = "HH:mm") val openingTime: LocalTime,
    @field:JsonFormat(pattern = "HH:mm") @get:JsonFormat(pattern = "HH:mm") val closingTime: LocalTime,
    val status: SpaceStatus,
) {
    companion object {
        @JvmStatic
        fun from(space: Space): SpaceResponse =
            SpaceResponse(
                space.id,
                space.name,
                space.location,
                space.capacity,
                space.pricePerSlot,
                space.imagePath,
                space.openingTime,
                space.closingTime,
                space.status,
            )
    }
}
