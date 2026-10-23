package com.ovengers.slotkey.credit.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.valueextraction.Unwrapping
import java.util.Optional

@JvmRecord
data class ChargeOrderConfirmRequest(
    @field:NotBlank(message = "paymentKey는 필수입니다.", payload = [Unwrapping.Unwrap::class])
    val paymentKey: Optional<String>,
    @field:NotBlank(message = "orderId는 필수입니다.", payload = [Unwrapping.Unwrap::class])
    val orderId: Optional<String>,
    @field:NotNull(message = "충전 금액은 필수입니다.", payload = [Unwrapping.Unwrap::class])
    val amount: Optional<Long>,
)
