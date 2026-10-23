package com.ovengers.slotkey.reservation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Schema(description = "결제 전 슬롯 임시 확보 요청")
@JvmRecord
data class ReservationCreateRequest(
    @get:Schema(description = "예약할 공간 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull
    val spaceId: Long?,
    @get:Schema(description = "이용 날짜", example = "2026-10-01", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull
    val date: LocalDate?,
    @get:Schema(
        description = "이용 시작 시각 (30분 단위)",
        example = "09:00:00",
        type = "string",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotNull
    val startTime: LocalTime?,
    @get:Schema(
        description = "같은 날짜의 이용 종료 시각 (30분 단위)",
        example = "10:00:00",
        type = "string",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotNull
    val endTime: LocalTime?,
) {
    fun toStartDateTime(): LocalDateTime = LocalDateTime.of(requireNotNull(date), requireNotNull(startTime))

    fun toEndDateTime(): LocalDateTime = LocalDateTime.of(requireNotNull(date), requireNotNull(endTime))
}
