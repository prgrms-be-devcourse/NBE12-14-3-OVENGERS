package com.ovengers.slotkey.auth.dto.response

import java.time.LocalDateTime

@JvmRecord
data class VerificationIssueResponse(
    val challengeId: Long,
    val expiresAt: LocalDateTime,
    val resendAvailableAt: LocalDateTime,
)
