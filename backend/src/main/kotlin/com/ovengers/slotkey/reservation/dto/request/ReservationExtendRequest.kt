package com.ovengers.slotkey.reservation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

@Schema(description = "예약 이용 시간 연장 요청")
@JvmRecord
data class ReservationExtendRequest(
    @get:Schema(
        description = "조회한 예약의 기존 종료 시각. 다른 요청에 의해 변경되었는지 확인하는 기준입니다.",
        example = "2026-10-01T10:00:00",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotNull
    val expectedEndTime: LocalDateTime?,
    @get:Schema(
        description = "연장할 종료 시각. 기존 종료 시각보다 늦어야 하며 30분 단위로 지정합니다.",
        example = "2026-10-01T10:30:00",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @field:NotNull
    val newEndTime: LocalDateTime?,
)
