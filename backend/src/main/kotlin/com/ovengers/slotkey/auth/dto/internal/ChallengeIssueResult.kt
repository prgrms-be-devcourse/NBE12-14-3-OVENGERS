package com.ovengers.slotkey.auth.dto.internal

import java.time.LocalDateTime

@JvmRecord
data class ChallengeIssueResult(
    val challengeId: Long,
    val expiresAt: LocalDateTime,
    val resendAvailableAt: LocalDateTime,
)
