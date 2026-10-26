package com.ovengers.slotkey.auth.dto.internal

import java.time.LocalDateTime

@JvmRecord
data class LoginResult(
    val accessToken: String,
    val refreshToken: String,
    val sessionExpiresAt: LocalDateTime,
)
