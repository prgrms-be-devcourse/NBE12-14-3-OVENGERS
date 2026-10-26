package com.ovengers.slotkey.auth.dto.request

import com.ovengers.slotkey.auth.entity.VerificationPurpose
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

@JvmRecord
data class VerificationCheckRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String,
    @field:NotNull
    val purpose: VerificationPurpose,
    @field:NotNull
    @field:Positive
    val challengeId: Long,
    @field:NotBlank
    @field:Pattern(
        regexp = "[0-9]{8}",
        message = "인증번호는 8자리 숫자여야 합니다.",
    )
    val code: String,
)
