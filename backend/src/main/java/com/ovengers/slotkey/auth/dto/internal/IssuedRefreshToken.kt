package com.ovengers.slotkey.auth.dto.internal

import java.time.LocalDateTime

@JvmRecord
data class IssuedRefreshToken(
    val rawToken: String,
    val sessionId: String,
    val sessionExpiresAt: LocalDateTime,
)
