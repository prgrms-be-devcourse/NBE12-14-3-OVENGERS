package com.ovengers.slotkey.notification.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "notification")
class Notification protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    final var id: Long? = null
        private set

    @get:JvmName("getMemberIdValue")
    @field:Column(name = "member_id", nullable = false)
    final var memberId: Long = 0
        private set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "type", nullable = false, length = 40)
    final var type: NotificationType = NotificationType.WAITLIST_SLOT_RELEASED
        private set

    @field:Column(name = "waitlist_entry_id")
    final var waitlistEntryId: Long? = null
        private set

    @field:Column(name = "reference_id", nullable = false)
    final var referenceId: Long = 0
        private set

    @field:Column(name = "event_id", nullable = false, length = 36)
    final lateinit var eventId: String
        private set

    @field:Column(name = "message", nullable = false, length = 500)
    final lateinit var message: String
        private set

    @field:Column(name = "read_at")
    final var readAt: LocalDateTime? = null
        private set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    final lateinit var createdAt: LocalDateTime
        private set

    constructor(
        memberId: Long?,
        waitlistEntryId: Long?,
        eventId: String,
        message: String,
        createdAt: LocalDateTime,
    ) : this() {
        this.memberId = requireNotNull(memberId)
        this.waitlistEntryId = requireNotNull(waitlistEntryId)
        this.referenceId = requireNotNull(waitlistEntryId)
        this.eventId = eventId
        this.message = message
        this.createdAt = createdAt
    }

    fun getMemberId(): Long? = memberId

    companion object {
        /** 대기자 알림이 아닌 이벤트 알림. `waitlist_entry_id` 없이 `referenceId`(예약·문의 id 등)만 가진다. */
        fun of(
            memberId: Long,
            type: NotificationType,
            referenceId: Long,
            eventId: String,
            message: String,
            createdAt: LocalDateTime,
        ): Notification =
            Notification().apply {
                this.memberId = memberId
                this.type = type
                this.referenceId = referenceId
                this.eventId = eventId
                this.message = message
                this.createdAt = createdAt
            }
    }
}
