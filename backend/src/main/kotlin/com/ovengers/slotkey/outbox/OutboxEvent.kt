package com.ovengers.slotkey.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "outbox_event")
class OutboxEvent protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    final var id: Long? = null
        private set

    @field:Column(name = "event_id", nullable = false, length = 36, unique = true)
    final lateinit var eventId: String
        private set

    @field:Column(name = "event_type", nullable = false, length = 50)
    final var eventType: String = "SlotReleased"
        private set

    @field:Column(nullable = false, length = 100)
    final var topic: String = "slot-released"
        private set

    @field:Column(name = "partition_key", nullable = false, length = 100)
    final lateinit var partitionKey: String
        private set

    @field:Column(nullable = false, columnDefinition = "json")
    final lateinit var payload: String
        private set

    @field:Column(nullable = false, length = 20)
    final var status: String = "PENDING"
        private set

    @field:Column(nullable = false)
    final var attempts: Int = 0
        private set

    @field:Column(name = "created_at", nullable = false)
    final lateinit var createdAt: LocalDateTime
        private set

    @field:Column(name = "published_at")
    final var publishedAt: LocalDateTime? = null
        private set

    constructor(
        eventId: String,
        spaceId: Long?,
        payload: String,
        createdAt: LocalDateTime,
    ) : this() {
        this.eventId = eventId
        this.partitionKey = requireNotNull(spaceId).toString()
        this.payload = payload
        this.createdAt = createdAt
    }

    constructor(
        eventId: String,
        eventType: String,
        topic: String,
        partitionKey: String,
        payload: String,
        createdAt: LocalDateTime,
    ) : this() {
        this.eventId = eventId
        this.eventType = eventType
        this.topic = topic
        this.partitionKey = partitionKey
        this.payload = payload
        this.createdAt = createdAt
    }
}
