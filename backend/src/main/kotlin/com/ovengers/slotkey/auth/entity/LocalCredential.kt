
package com.ovengers.slotkey.auth.entity

import com.ovengers.slotkey.member.entity.Member
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapsId
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "local_credential")
class LocalCredential protected constructor() {
    @field:Id
    @field:Column(name = "member_id")
    var memberId: Long? = null
        protected set

    @field:MapsId
    @field:OneToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "member_id", nullable = false)
    lateinit var member: Member
        protected set

    @field:Column(name = "password_hash", nullable = false, length = 255)
    lateinit var passwordHash: String
        protected set

    @field:Column(name = "password_changed_at", nullable = false)
    lateinit var passwordChangedAt: LocalDateTime
        protected set

    constructor(
        member: Member,
        passwordHash: String,
        passwordChangedAt: LocalDateTime,
    ) : this() {
        this.member = member
        this.passwordHash = passwordHash
        this.passwordChangedAt = passwordChangedAt
    }

    fun changePassword(
        passwordHash: String,
        changedAt: LocalDateTime,
    ) {
        this.passwordHash = passwordHash
        passwordChangedAt = changedAt
    }
}
