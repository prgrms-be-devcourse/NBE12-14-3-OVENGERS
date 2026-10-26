
package com.ovengers.slotkey.auth.entity

import com.ovengers.slotkey.member.entity.Member
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
@Table(name = "refresh_token")
class RefreshToken protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "member_id", nullable = false)
    lateinit var member: Member
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "auth_session_id", nullable = false)
    lateinit var authSession: AuthSession
        protected set

    @field:Column(name = "token_hash", nullable = false, unique = true, length = 64)
    lateinit var tokenHash: String
        protected set

    @field:Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: LocalDateTime
        protected set

    @field:Column(name = "revoked_at")
    var revokedAt: LocalDateTime? = null
        protected set

    @field:Column(name = "consumed_at")
    var consumedAt: LocalDateTime? = null
        protected set

    constructor(
        member: Member,
        authSession: AuthSession,
        tokenHash: String,
        expiresAt: LocalDateTime,
    ) : this() {
        this.member = member
        this.authSession = authSession
        this.tokenHash = tokenHash
        this.expiresAt = expiresAt
    }

    fun isRevoked(): Boolean = revokedAt != null

    fun revoke(now: LocalDateTime) {
        if (revokedAt == null) revokedAt = now
    }

    fun isConsumed(): Boolean = consumedAt != null

    fun consume(now: LocalDateTime) {
        if (consumedAt == null) consumedAt = now
    }

    fun isExpired(now: LocalDateTime): Boolean = !now.isBefore(expiresAt)
}
