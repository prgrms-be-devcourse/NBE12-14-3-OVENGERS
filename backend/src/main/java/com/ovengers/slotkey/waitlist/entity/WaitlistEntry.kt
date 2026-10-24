package com.ovengers.slotkey.waitlist.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.LocalDateTime

@Entity
@DynamicUpdate
@Table(name = "waitlist_entry")
class WaitlistEntry protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @get:JvmName("getMemberIdValue")
    @field:Column(name = "member_id", nullable = false)
    final var memberId: Long = 0
        protected set

    @get:JvmName("getSpaceIdValue")
    @field:Column(name = "space_id", nullable = false)
    final var spaceId: Long = 0
        protected set

    @field:Column(name = "start_time", nullable = false)
    lateinit var startTime: LocalDateTime
        protected set

    @field:Column(name = "end_time", nullable = false)
    lateinit var endTime: LocalDateTime
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "status", nullable = false, length = 20)
    lateinit var status: WaitlistStatus
        protected set

    @field:Column(name = "active")
    var active: Int? = null
        protected set

    @field:Column(name = "last_notified_at")
    var lastNotifiedAt: LocalDateTime? = null
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    private constructor(
        memberId: Long?,
        spaceId: Long?,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        status: WaitlistStatus,
        active: Int?,
        createdAt: LocalDateTime,
    ) : this() {
        this.memberId = requireNotNull(memberId)
        this.spaceId = requireNotNull(spaceId)
        this.startTime = startTime
        this.endTime = endTime
        this.status = status
        this.active = active
        this.createdAt = createdAt
    }

    fun cancel() {
        if (status != WaitlistStatus.WAITING) return
        status = WaitlistStatus.CANCELLED
        active = null
    }

    fun getMemberId(): Long? = memberId

    fun getSpaceId(): Long? = spaceId

    companion object {
        private const val ACTIVE = 1

        @JvmStatic
        fun waiting(
            memberId: Long?,
            spaceId: Long?,
            startTime: LocalDateTime,
            endTime: LocalDateTime,
            now: LocalDateTime,
        ): WaitlistEntry =
            WaitlistEntry(
                memberId,
                spaceId,
                startTime,
                endTime,
                WaitlistStatus.WAITING,
                ACTIVE,
                now,
            )
    }
}
