package com.ovengers.slotkey.auth.dto.internal

import java.time.LocalDateTime

@JvmRecord
data class RefreshResult(
    val accessToken: String,
    val refreshToken: String,
    val sessionExpiresAt: LocalDateTime,
)
