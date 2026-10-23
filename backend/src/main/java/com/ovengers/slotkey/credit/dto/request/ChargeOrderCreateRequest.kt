package com.ovengers.slotkey.credit.dto.request

import jakarta.validation.constraints.NotNull
import jakarta.validation.valueextraction.Unwrapping
import java.util.Optional

@JvmRecord
data class ChargeOrderCreateRequest(
    @field:NotNull(message = "충전 금액은 필수입니다.", payload = [Unwrapping.Unwrap::class])
    val amount: Optional<Long>,
)
