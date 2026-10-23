package com.ovengers.slotkey.waitlist.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

@JvmRecord
data class WaitlistApplyRequest(
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull
    val spaceId: Long?,
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull
    val startTime: LocalDateTime?,
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull
    val endTime: LocalDateTime?,
)
