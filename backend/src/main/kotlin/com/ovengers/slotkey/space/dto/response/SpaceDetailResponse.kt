package com.ovengers.slotkey.space.dto.response

import com.fasterxml.jackson.annotation.JsonFormat
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import java.time.LocalTime

@JvmRecord
data class SpaceDetailResponse(
    val id: Long,
    val name: String,
    val location: String,
    val description: String?,
    val capacity: Int,
    val pricePerSlot: Long,
    val imagePath: String?,
    @field:JsonFormat(pattern = "HH:mm") @get:JsonFormat(pattern = "HH:mm") val openingTime: LocalTime,
    @field:JsonFormat(pattern = "HH:mm") @get:JsonFormat(pattern = "HH:mm") val closingTime: LocalTime,
    val status: SpaceStatus,
    val version: Int,
) {
    companion object {
        @JvmStatic
        fun from(space: Space): SpaceDetailResponse =
            builder()
                .id(space.id)
                .name(space.name)
                .location(space.location)
                .description(space.description)
                .capacity(space.capacity)
                .pricePerSlot(space.pricePerSlot)
                .imagePath(space.imagePath)
                .openingTime(space.openingTime)
                .closingTime(space.closingTime)
                .status(space.status)
                .version(space.version)
                .build()

        @JvmStatic
        fun builder(): SpaceDetailResponseBuilder = SpaceDetailResponseBuilder()
    }

    class SpaceDetailResponseBuilder {
        private var id: Long? = null
        private var name: String? = null
        private var location: String? = null
        private var description: String? = null
        private var capacity: Int? = null
        private var pricePerSlot: Long? = null
        private var imagePath: String? = null
        private var openingTime: LocalTime? = null
        private var closingTime: LocalTime? = null
        private var status: SpaceStatus? = null
        private var version: Int? = null

        fun id(id: Long): SpaceDetailResponseBuilder = apply { this.id = id }

        fun name(name: String): SpaceDetailResponseBuilder = apply { this.name = name }

        fun location(location: String): SpaceDetailResponseBuilder = apply { this.location = location }

        fun description(description: String?): SpaceDetailResponseBuilder = apply { this.description = description }

        fun capacity(capacity: Int): SpaceDetailResponseBuilder = apply { this.capacity = capacity }

        fun pricePerSlot(pricePerSlot: Long): SpaceDetailResponseBuilder = apply { this.pricePerSlot = pricePerSlot }

        fun imagePath(imagePath: String?): SpaceDetailResponseBuilder = apply { this.imagePath = imagePath }

        fun openingTime(openingTime: LocalTime): SpaceDetailResponseBuilder = apply { this.openingTime = openingTime }

        fun closingTime(closingTime: LocalTime): SpaceDetailResponseBuilder = apply { this.closingTime = closingTime }

        fun status(status: SpaceStatus): SpaceDetailResponseBuilder = apply { this.status = status }

        fun version(version: Int): SpaceDetailResponseBuilder = apply { this.version = version }

        fun build(): SpaceDetailResponse =
            SpaceDetailResponse(
                requireNotNull(id) { "id is required" },
                requireNotNull(name) { "name is required" },
                requireNotNull(location) { "location is required" },
                description,
                requireNotNull(capacity) { "capacity is required" },
                requireNotNull(pricePerSlot) { "pricePerSlot is required" },
                imagePath,
                requireNotNull(openingTime) { "openingTime is required" },
                requireNotNull(closingTime) { "closingTime is required" },
                requireNotNull(status) { "status is required" },
                requireNotNull(version) { "version is required" },
            )
    }
}
