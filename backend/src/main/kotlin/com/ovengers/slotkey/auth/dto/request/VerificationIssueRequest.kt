package com.ovengers.slotkey.auth.dto.request

import com.ovengers.slotkey.auth.entity.VerificationPurpose
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@JvmRecord
data class VerificationIssueRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String,
    @field:NotNull
    val purpose: VerificationPurpose,
)
