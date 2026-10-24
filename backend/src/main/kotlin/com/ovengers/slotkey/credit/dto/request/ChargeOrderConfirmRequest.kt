package com.ovengers.slotkey.credit.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

@JvmRecord
data class ChargeOrderConfirmRequest(
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "paymentKey는 필수입니다.")
    val paymentKey: String?,
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "orderId는 필수입니다.")
    val orderId: String?,
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "충전 금액은 필수입니다.")
    val amount: Long?,
)
