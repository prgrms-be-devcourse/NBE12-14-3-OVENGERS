package com.ovengers.slotkey.credit.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "charge_order",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_charge_order_order_id",
            columnNames = ["order_id"],
        ),
        UniqueConstraint(
            name = "uk_charge_order_member_client_idempotency",
            columnNames = ["member_id", "client_idempotency_key"],
        ),
    ],
)
class ChargeOrder protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    final var id: Long? = null
        private set

    @field:Column(name = "order_id", nullable = false, length = 64, unique = true)
    final lateinit var orderId: String
        private set

    @get:JvmName("getMemberIdValue")
    @field:Column(name = "member_id", nullable = false)
    final var memberId: Long = 0
        private set

    @field:Column(nullable = false)
    final var amount: Long = 0
        private set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    final lateinit var status: ChargeOrderStatus
        private set

    @field:Column(name = "payment_key", length = 200, unique = true)
    final var paymentKey: String? = null
        private set

    @field:Column(name = "client_idempotency_key", length = 100)
    final var clientIdempotencyKey: String? = null
        private set

    @field:Column(name = "pg_idempotency_key", length = 100, unique = true)
    final var pgIdempotencyKey: String? = null
        private set

    @field:Column(name = "payment_method", length = 30)
    final var paymentMethod: String? = null
        private set

    @field:Column(name = "payment_provider", length = 30)
    final var paymentProvider: String? = null
        private set

    @field:Column(name = "failure_code", length = 100)
    final var failureCode: String? = null
        private set

    @field:Column(name = "recovery_next_attempt_at")
    final var recoveryNextAttemptAt: LocalDateTime? = null
        private set

    @field:Column(name = "recovery_exhausted_at")
    final var recoveryExhaustedAt: LocalDateTime? = null
        private set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    final lateinit var createdAt: LocalDateTime
        private set

    @field:Column(name = "updated_at", nullable = false)
    final lateinit var updatedAt: LocalDateTime
        private set

    private constructor(
        memberId: Long,
        orderId: String,
        amount: Long,
        status: ChargeOrderStatus,
        createdAt: LocalDateTime,
    ) : this() {
        this.memberId = memberId
        this.orderId = orderId
        this.amount = amount
        this.status = status
        this.createdAt = createdAt
        updatedAt = createdAt
    }

    fun getMemberId(): Long? = memberId

    /** PG가 알려 준 결제수단(표시용). 결제 처리와 무관하며 값이 없으면 기록하지 않는다. */
    fun recordPaymentMethod(
        paymentMethod: String?,
        paymentProvider: String?,
    ) {
        this.paymentMethod = paymentMethod
        this.paymentProvider = paymentProvider
    }

    fun markPaid(now: LocalDateTime) {
        status = ChargeOrderStatus.PAID
        failureCode = null
        recoveryNextAttemptAt = null
        updatedAt = now
    }

    fun markFailed(
        failureCode: String,
        now: LocalDateTime,
    ) {
        status = ChargeOrderStatus.FAILED
        this.failureCode = failureCode
        recoveryNextAttemptAt = null
        updatedAt = now
    }

    fun scheduleNextRecovery(nextAttemptAt: LocalDateTime?) {
        recoveryNextAttemptAt = nextAttemptAt
    }

    fun markRecoveryExhausted(now: LocalDateTime) {
        recoveryExhaustedAt = now
        recoveryNextAttemptAt = null
    }

    companion object {
        @JvmStatic
        fun createPending(
            memberId: Long?,
            orderId: String,
            amount: Long,
            createdAt: LocalDateTime,
        ): ChargeOrder =
            ChargeOrder(
                memberId = requireNotNull(memberId),
                orderId = orderId,
                amount = amount,
                status = ChargeOrderStatus.PENDING,
                createdAt = createdAt,
            )
    }
}
