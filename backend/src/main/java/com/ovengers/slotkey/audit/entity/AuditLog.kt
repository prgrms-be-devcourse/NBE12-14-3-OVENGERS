package com.ovengers.slotkey.audit.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@Entity
@Table(name = "audit_logs")
@EntityListeners(AuditingEntityListener::class)
class AuditLog() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    final var id: Long? = null
        private set

    @field:Column(name = "actor_member_id")
    final var actorMemberId: Long? = null
        private set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30)
    final lateinit var action: AuditAction
        private set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20, name = "target_type")
    final lateinit var targetType: AuditTargetType
        private set

    @get:JvmName("getTargetIdValue")
    @field:Column(nullable = false, name = "target_id")
    final var targetId: Long = 0
        private set

    @field:Column(length = 500)
    final var reason: String? = null
        private set

    @field:Column(columnDefinition = "TEXT", name = "before_value")
    final var beforeValue: String? = null
        private set

    @field:Column(columnDefinition = "TEXT", name = "after_value")
    final var afterValue: String? = null
        private set

    @field:CreatedDate
    @field:Column(nullable = false, name = "created_at", updatable = false)
    final var createdAt: LocalDateTime? = null
        private set

    constructor(
        id: Long?,
        actorMemberId: Long?,
        action: AuditAction,
        targetType: AuditTargetType,
        targetId: Long?,
        reason: String?,
        beforeValue: String?,
        afterValue: String?,
        createdAt: LocalDateTime?,
    ) : this() {
        this.id = id
        this.actorMemberId = actorMemberId
        this.action = action
        this.targetType = targetType
        this.targetId = requireNotNull(targetId)
        this.reason = reason
        this.beforeValue = beforeValue
        this.afterValue = afterValue
        this.createdAt = createdAt
    }

    fun getTargetId(): Long? = targetId

    class AuditLogBuilder internal constructor() {
        private var id: Long? = null
        private var actorMemberId: Long? = null
        private var action: AuditAction? = null
        private var targetType: AuditTargetType? = null
        private var targetId: Long? = null
        private var reason: String? = null
        private var beforeValue: String? = null
        private var afterValue: String? = null
        private var createdAt: LocalDateTime? = null

        fun id(id: Long?) = apply { this.id = id }

        fun actorMemberId(actorMemberId: Long?) = apply { this.actorMemberId = actorMemberId }

        fun action(action: AuditAction) = apply { this.action = action }

        fun targetType(targetType: AuditTargetType) = apply { this.targetType = targetType }

        fun targetId(targetId: Long?) = apply { this.targetId = targetId }

        fun reason(reason: String?) = apply { this.reason = reason }

        fun beforeValue(beforeValue: String?) = apply { this.beforeValue = beforeValue }

        fun afterValue(afterValue: String?) = apply { this.afterValue = afterValue }

        fun createdAt(createdAt: LocalDateTime?) = apply { this.createdAt = createdAt }

        fun build(): AuditLog =
            AuditLog(
                id,
                actorMemberId,
                requireNotNull(action),
                requireNotNull(targetType),
                targetId,
                reason,
                beforeValue,
                afterValue,
                createdAt,
            )

        override fun toString(): String =
            "AuditLog.AuditLogBuilder(id=$id, actorMemberId=$actorMemberId, action=$action, " +
                "targetType=$targetType, targetId=$targetId, reason=$reason, " +
                "beforeValue=$beforeValue, afterValue=$afterValue, createdAt=$createdAt)"
    }

    companion object {
        @JvmStatic
        fun builder(): AuditLogBuilder = AuditLogBuilder()
    }
}
