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
                space.id ?: 0L,
                space.name ?: "",
                space.location ?: "",
                space.capacity ?: 0,
                space.pricePerSlot ?: 0L,
                space.imagePath,
                space.openingTime ?: LocalTime.MIN,
                space.closingTime ?: LocalTime.MAX,
                space.status ?: SpaceStatus.INACTIVE,
            )
    }
}
