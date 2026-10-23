package com.ovengers.slotkey.auth.dto.response

import java.time.LocalDateTime

@JvmRecord
data class VerificationCheckResponse(
    val grant: String,
    val expiresAt: LocalDateTime,
)
