package com.ovengers.slotkey.auth.dto.internal

import java.time.LocalDateTime

@JvmRecord
data class VerificationCheckResult(
    val status: Status,
    val grant: String?,
    val expiresAt: LocalDateTime?,
    val blockedUntil: LocalDateTime?,
) {
    enum class Status {
        SUCCESS,
        WRONG_CODE,
        INVALID_CHALLENGE,
        TEMPORARILY_BLOCKED,
        ADMIN_LOCKED,
    }

    companion object {
        @JvmStatic
        fun failure(
            status: Status,
            blockedUntil: LocalDateTime?,
        ): VerificationCheckResult =
            VerificationCheckResult(
                status,
                null,
                null,
                blockedUntil,
            )
    }
}
