package com.ovengers.slotkey.access.entity

import com.ovengers.slotkey.reservation.entity.Reservation
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
@Table(name = "door_access_token")
class DoorAccessToken protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    // 기존 Java 생성자·getter의 null 허용을 유지하며, DB 필수 조건은 기존 매핑으로 검증한다.
    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "reservation_id", nullable = false)
    var reservation: Reservation? = null
        protected set

    @field:Column(name = "token_hash", nullable = false, unique = true)
    var tokenHash: String? = null
        protected set

    @field:Column(name = "issued_at", nullable = false)
    var issuedAt: LocalDateTime? = null
        protected set

    @field:Column(name = "revoked_at")
    var revokedAt: LocalDateTime? = null
        protected set

    @field:Column(name = "revoke_reason", length = 100)
    var revokeReason: String? = null
        protected set

    constructor(
        reservation: Reservation?,
        tokenHash: String?,
        issuedAt: LocalDateTime?,
    ) : this() {
        this.reservation = reservation
        this.tokenHash = tokenHash
        this.issuedAt = issuedAt
    }

    fun revoke(
        revokedAt: LocalDateTime?,
        revokeReason: String?,
    ) {
        this.revokedAt = revokedAt
        this.revokeReason = revokeReason
    }

    val isRevoked: Boolean
        get() = revokedAt != null
}
