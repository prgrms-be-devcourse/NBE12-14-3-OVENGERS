package com.ovengers.slotkey.reservation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.valueextraction.Unwrapping
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Optional

@Schema(description = "결제 전 슬롯 임시 확보 요청")
@JvmRecord
data class ReservationCreateRequest(
    @get:Schema(description = "예약할 공간 ID", example = "1")
    @field:NotNull(payload = [Unwrapping.Unwrap::class])
    val spaceId: Optional<Long>,
    @get:Schema(description = "이용 날짜", example = "2026-10-01")
    @field:NotNull(payload = [Unwrapping.Unwrap::class])
    val date: Optional<LocalDate>,
    @get:Schema(description = "이용 시작 시각 (30분 단위)", example = "09:00:00", type = "string")
    @field:NotNull(payload = [Unwrapping.Unwrap::class])
    val startTime: Optional<LocalTime>,
    @get:Schema(description = "같은 날짜의 이용 종료 시각 (30분 단위)", example = "10:00:00", type = "string")
    @field:NotNull(payload = [Unwrapping.Unwrap::class])
    val endTime: Optional<LocalTime>,
) {
    fun toStartDateTime(): LocalDateTime = LocalDateTime.of(date.orElseThrow(), startTime.orElseThrow())

    fun toEndDateTime(): LocalDateTime = LocalDateTime.of(date.orElseThrow(), endTime.orElseThrow())
}
