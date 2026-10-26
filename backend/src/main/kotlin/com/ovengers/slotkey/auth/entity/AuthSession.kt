
package com.ovengers.slotkey.auth.entity

import com.ovengers.slotkey.member.entity.Member
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "auth_session")
class AuthSession protected constructor() {
    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "member_id", nullable = false)
    lateinit var member: Member
        protected set

    @field:Column(name = "created_at", nullable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    @field:Id
    @field:Column(name = "id", nullable = false, length = 36)
    lateinit var id: String
        protected set

    @field:Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: LocalDateTime
        protected set

    @field:Column(name = "revoked_at")
    var revokedAt: LocalDateTime? = null
        protected set

    constructor(
        member: Member,
        createdAt: LocalDateTime,
    ) : this() {
        this.id = UUID.randomUUID().toString()
        this.member = member
        this.createdAt = createdAt
        this.expiresAt = createdAt.plusHours(24)
    }

    fun isActiveAt(now: LocalDateTime): Boolean = revokedAt == null && now.isBefore(expiresAt)

    fun revoke(now: LocalDateTime) {
        if (revokedAt == null) revokedAt = now
    }
}
