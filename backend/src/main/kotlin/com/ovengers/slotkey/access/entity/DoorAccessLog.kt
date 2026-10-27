package com.ovengers.slotkey.access.entity

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.space.entity.Space
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "door_access_log")
class DoorAccessLog protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "actor_member_id")
    var actorMember: Member? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "reservation_id")
    var reservation: Reservation? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "requested_space_id")
    var requestedSpace: Space? = null
        protected set

    // 기존 Java 생성자·getter의 null 허용을 유지하며, DB 필수 조건은 기존 매핑으로 검증한다.
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    var result: AccessResult? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "reason_code", length = 50)
    var reasonCode: AccessDenyReason? = null
        protected set

    @field:Column(name = "attempted_at", nullable = false)
    var attemptedAt: LocalDateTime? = null
        protected set

    constructor(
        actorMember: Member?,
        reservation: Reservation?,
        requestedSpace: Space?,
        result: AccessResult?,
        reasonCode: AccessDenyReason?,
        attemptedAt: LocalDateTime?,
    ) : this() {
        this.actorMember = actorMember
        this.reservation = reservation
        this.requestedSpace = requestedSpace
        this.result = result
        this.reasonCode = reasonCode
        this.attemptedAt = attemptedAt
    }
}
