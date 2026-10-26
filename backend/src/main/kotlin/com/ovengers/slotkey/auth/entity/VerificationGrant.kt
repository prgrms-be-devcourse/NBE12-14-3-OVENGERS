
package com.ovengers.slotkey.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "verification_grant",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_verification_grant_challenge",
            columnNames = ["challenge_id"],
        ),
        UniqueConstraint(
            name = "uk_verification_grant_hash",
            columnNames = ["grant_hash"],
        ),
    ],
)
class VerificationGrant protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:OneToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "challenge_id", nullable = false)
    lateinit var challenge: VerificationChallenge
        protected set

    @field:Column(name = "grant_hash", nullable = false, length = 64)
    lateinit var grantHash: String
        protected set

    @field:Column(name = "issued_at", nullable = false)
    lateinit var issuedAt: LocalDateTime
        protected set

    @field:Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: LocalDateTime
        protected set

    @field:Column(name = "consumed_at")
    var consumedAt: LocalDateTime? = null
        protected set

    @field:Column(name = "invalidated_at")
    var invalidatedAt: LocalDateTime? = null
        protected set

    constructor(
        challenge: VerificationChallenge,
        grantHash: String,
        issuedAt: LocalDateTime,
    ) : this() {
        require(challenge.verifiedAt != null) {
            "인증 완료된 챌린지가 필요합니다."
        }

        this.challenge = challenge
        this.grantHash = grantHash
        this.issuedAt = issuedAt
        expiresAt = issuedAt.plusMinutes(5)
    }

    fun isUsable(now: LocalDateTime): Boolean =
        consumedAt == null &&
            invalidatedAt == null &&
            now.isBefore(expiresAt)

    fun consume(now: LocalDateTime) {
        check(isUsable(now)) {
            "사용할 수 없는 인증 증표입니다."
        }
        consumedAt = now
    }

    fun invalidate(now: LocalDateTime) {
        if (invalidatedAt == null) {
            invalidatedAt = now
        }
    }
}
