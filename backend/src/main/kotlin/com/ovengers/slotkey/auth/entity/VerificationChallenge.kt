
package com.ovengers.slotkey.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "verification_challenge")
class VerificationChallenge protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "guard_id", nullable = false)
    lateinit var guard: VerificationGuard
        protected set

    @field:Column(name = "code_hash", nullable = false, length = 64)
    lateinit var codeHash: String
        protected set

    @field:Column(name = "issued_at", nullable = false)
    lateinit var issuedAt: LocalDateTime
        protected set

    @field:Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: LocalDateTime
        protected set

    @field:Column(name = "verified_at")
    var verifiedAt: LocalDateTime? = null
        protected set

    @field:Column(name = "invalidated_at")
    var invalidatedAt: LocalDateTime? = null
        protected set

    constructor(
        guard: VerificationGuard,
        codeHash: String,
        issuedAt: LocalDateTime,
    ) : this() {
        this.guard = guard
        this.codeHash = codeHash
        this.issuedAt = issuedAt
        expiresAt = issuedAt.plusMinutes(3)
    }

    fun isUsable(now: LocalDateTime): Boolean =
        verifiedAt == null &&
            invalidatedAt == null &&
            now.isBefore(expiresAt)

    fun markVerified(now: LocalDateTime) {
        check(isUsable(now)) {
            "사용할 수 없는 인증번호입니다."
        }
        verifiedAt = now
    }

    fun invalidate(now: LocalDateTime) {
        if (invalidatedAt == null) {
            invalidatedAt = now
        }
    }
}
