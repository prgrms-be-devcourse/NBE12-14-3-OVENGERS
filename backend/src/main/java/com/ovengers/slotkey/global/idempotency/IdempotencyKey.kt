package com.ovengers.slotkey.global.idempotency

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "idempotency_key")
class IdempotencyKey() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    final var id: Long? = null
        private set

    @field:Column(name = "idempotency_key", nullable = false, length = 100)
    final lateinit var key: String
        private set

    @get:JvmName("getMemberIdValue")
    @field:Column(name = "member_id", nullable = false)
    final var memberId: Long = 0
        private set

    @field:Column(name = "request_path", nullable = false, length = 255)
    final lateinit var requestPath: String
        private set

    @field:Column(name = "response_status", nullable = false)
    final var responseStatus: Int = 0
        private set

    @field:Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    final lateinit var responseBody: String
        private set

    @field:Column(name = "created_at", nullable = false)
    final lateinit var createdAt: LocalDateTime
        private set

    constructor(
        id: Long?,
        key: String,
        memberId: Long?,
        requestPath: String,
        responseStatus: Int,
        responseBody: String,
        createdAt: LocalDateTime,
    ) : this() {
        this.id = id
        this.key = key
        this.memberId = requireNotNull(memberId)
        this.requestPath = requestPath
        this.responseStatus = responseStatus
        this.responseBody = responseBody
        this.createdAt = createdAt
    }

    fun getMemberId(): Long? = memberId

    class IdempotencyKeyBuilder internal constructor() {
        private var id: Long? = null
        private var key: String? = null
        private var memberId: Long? = null
        private var requestPath: String? = null
        private var responseStatus: Int = 0
        private var responseBody: String? = null
        private var createdAt: LocalDateTime? = null

        fun id(id: Long?) = apply { this.id = id }

        fun key(key: String) = apply { this.key = key }

        fun memberId(memberId: Long?) = apply { this.memberId = memberId }

        fun requestPath(requestPath: String) = apply { this.requestPath = requestPath }

        fun responseStatus(responseStatus: Int) = apply { this.responseStatus = responseStatus }

        fun responseBody(responseBody: String) = apply { this.responseBody = responseBody }

        fun createdAt(createdAt: LocalDateTime) = apply { this.createdAt = createdAt }

        fun build(): IdempotencyKey =
            IdempotencyKey(
                id,
                requireNotNull(key),
                memberId,
                requireNotNull(requestPath),
                responseStatus,
                requireNotNull(responseBody),
                requireNotNull(createdAt),
            )

        override fun toString(): String =
            "IdempotencyKey.IdempotencyKeyBuilder(id=$id, key=$key, memberId=$memberId, " +
                "requestPath=$requestPath, responseStatus=$responseStatus, " +
                "responseBody=$responseBody, createdAt=$createdAt)"
    }

    companion object {
        @JvmStatic
        fun of(
            key: String,
            memberId: Long?,
            requestPath: String,
            responseStatus: Int,
            responseBody: String,
            now: LocalDateTime,
        ): IdempotencyKey = IdempotencyKey(null, key, memberId, requestPath, responseStatus, responseBody, now)

        @JvmStatic
        fun builder(): IdempotencyKeyBuilder = IdempotencyKeyBuilder()
    }
}
