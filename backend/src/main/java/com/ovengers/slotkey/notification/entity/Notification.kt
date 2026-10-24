package com.ovengers.slotkey.notification.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
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

    @get:JvmName("getWaitlistEntryIdValue")
    @field:Column(name = "waitlist_entry_id", nullable = false)
    final var waitlistEntryId: Long = 0
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
        this.eventId = eventId
        this.message = message
        this.createdAt = createdAt
    }

    fun getMemberId(): Long? = memberId

    fun getWaitlistEntryId(): Long? = waitlistEntryId
}
