package com.ovengers.slotkey.reservation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import jakarta.validation.valueextraction.Unwrapping
import java.util.Optional

@JvmRecord
data class ForceCancelRequest(
    @get:Schema(maxLength = 500)
    @field:NotBlank(payload = [Unwrapping.Unwrap::class])
    @field:Size(max = 500, payload = [Unwrapping.Unwrap::class])
    val reason: Optional<String>,
)
