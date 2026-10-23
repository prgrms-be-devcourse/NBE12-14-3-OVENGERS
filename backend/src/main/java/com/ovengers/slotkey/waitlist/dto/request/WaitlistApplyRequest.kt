package com.ovengers.slotkey.waitlist.dto.request

import jakarta.validation.constraints.NotNull
import jakarta.validation.valueextraction.Unwrapping
import java.time.LocalDateTime
import java.util.Optional

@JvmRecord
data class WaitlistApplyRequest(
    @field:NotNull(payload = [Unwrapping.Unwrap::class])
    val spaceId: Optional<Long>,
    @field:NotNull(payload = [Unwrapping.Unwrap::class])
    val startTime: Optional<LocalDateTime>,
    @field:NotNull(payload = [Unwrapping.Unwrap::class])
    val endTime: Optional<LocalDateTime>,
)
