package com.ovengers.slotkey.space.dto.response

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@JvmRecord
@Schema(description = "30분 단위 공간 예약 슬롯")
data class SlotResponse(
    @field:Schema(
        description = "슬롯 시작 시각",
        example = "2026-10-01T09:00:00",
    )
    @get:Schema(
        description = "슬롯 시작 시각",
        example = "2026-10-01T09:00:00",
    )
    @field:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @get:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val slotStart: LocalDateTime,
    @field:Schema(
        description = "슬롯 종료 시각",
        example = "2026-10-01T09:30:00",
    )
    @get:Schema(
        description = "슬롯 종료 시각",
        example = "2026-10-01T09:30:00",
    )
    @field:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @get:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val slotEnd: LocalDateTime,
    @field:JsonProperty("isAvailable")
    @get:JsonProperty("isAvailable")
    @param:JsonProperty("isAvailable")
    @field:Schema(
        description = "조회 시점의 예약 가능 여부",
        example = "true",
    )
    @get:Schema(
        description = "조회 시점의 예약 가능 여부",
        example = "true",
    )
    val isAvailable: Boolean,
) {
    companion object {
        @JvmStatic
        fun of(
            slotStart: LocalDateTime,
            slotEnd: LocalDateTime,
            isAvailable: Boolean,
        ): SlotResponse = SlotResponse(slotStart, slotEnd, isAvailable)
    }
}
