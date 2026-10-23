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
            builder()
                .id(space.id)
                .name(space.name)
                .location(space.location)
                .capacity(space.capacity)
                .pricePerSlot(space.pricePerSlot)
                .imagePath(space.imagePath)
                .openingTime(space.openingTime)
                .closingTime(space.closingTime)
                .status(space.status)
                .build()

        @JvmStatic
        fun builder(): SpaceResponseBuilder = SpaceResponseBuilder()
    }

    class SpaceResponseBuilder {
        private var id: Long? = null
        private var name: String? = null
        private var location: String? = null
        private var capacity: Int? = null
        private var pricePerSlot: Long? = null
        private var imagePath: String? = null
        private var openingTime: LocalTime? = null
        private var closingTime: LocalTime? = null
        private var status: SpaceStatus? = null

        fun id(id: Long): SpaceResponseBuilder = apply { this.id = id }

        fun name(name: String): SpaceResponseBuilder = apply { this.name = name }

        fun location(location: String): SpaceResponseBuilder = apply { this.location = location }

        fun capacity(capacity: Int): SpaceResponseBuilder = apply { this.capacity = capacity }

        fun pricePerSlot(pricePerSlot: Long): SpaceResponseBuilder = apply { this.pricePerSlot = pricePerSlot }

        fun imagePath(imagePath: String?): SpaceResponseBuilder = apply { this.imagePath = imagePath }

        fun openingTime(openingTime: LocalTime): SpaceResponseBuilder = apply { this.openingTime = openingTime }

        fun closingTime(closingTime: LocalTime): SpaceResponseBuilder = apply { this.closingTime = closingTime }

        fun status(status: SpaceStatus): SpaceResponseBuilder = apply { this.status = status }

        fun build(): SpaceResponse =
            SpaceResponse(
                requireNotNull(id) { "id is required" },
                requireNotNull(name) { "name is required" },
                requireNotNull(location) { "location is required" },
                requireNotNull(capacity) { "capacity is required" },
                requireNotNull(pricePerSlot) { "pricePerSlot is required" },
                imagePath,
                requireNotNull(openingTime) { "openingTime is required" },
                requireNotNull(closingTime) { "closingTime is required" },
                requireNotNull(status) { "status is required" },
            )
    }
}
