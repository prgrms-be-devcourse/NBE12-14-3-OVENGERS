package com.ovengers.slotkey.reservation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.valueextraction.Unwrapping
import java.util.Optional

@Schema(description = "크레딧 결제 요청. Idempotency-Key는 요청 헤더로 전달합니다.")
@JvmRecord
data class ReservationPayRequest(
    @get:Schema(
        description = "예약 생성 응답의 spaceVersion을 그대로 전달합니다.",
        example = "0",
    )
    @field:NotNull(payload = [Unwrapping.Unwrap::class])
    val spaceVersion: Optional<Int>,
)
