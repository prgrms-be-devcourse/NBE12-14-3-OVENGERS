package com.ovengers.slotkey.credit.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull

@JvmRecord
data class ChargeOrderCreateRequest(
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "충전 금액은 필수입니다.")
    val amount: Long?,
)
