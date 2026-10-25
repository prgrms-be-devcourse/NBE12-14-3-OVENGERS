package com.ovengers.slotkey.credit.entity

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.reservation.entity.Reservation
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
@Table(name = "credit_transaction")
class CreditTransaction {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "member_id", nullable = false)
    lateinit var member: Member
        protected set

    @field:Column(nullable = false)
    var amount: Int = 0
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30)
    lateinit var type: CreditTransactionType
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "reservation_id")
    var reservation: Reservation? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "charge_order_id")
    var chargeOrder: ChargeOrder? = null
        protected set

    @field:Column(name = "balance_after", nullable = false)
    var balanceAfter: Int = 0
        protected set

    @field:Column(length = 500)
    var reason: String? = null
        protected set

    @field:Column(name = "created_at", nullable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    protected constructor()

    constructor(
        member: Member,
        amount: Int,
        type: CreditTransactionType,
        reservation: Reservation?,
        balanceAfter: Int,
        reason: String?,
        createdAt: LocalDateTime,
    ) : this() {
        this.member = member
        this.amount = amount
        this.type = type
        this.reservation = reservation
        this.balanceAfter = balanceAfter
        this.reason = reason
        this.createdAt = createdAt
    }

    companion object {
        @JvmStatic
        fun topUp(
            member: Member,
            amount: Int,
            chargeOrder: ChargeOrder,
            balanceAfter: Int,
            createdAt: LocalDateTime,
        ): CreditTransaction {
            val transaction = CreditTransaction()
            transaction.member = member
            transaction.amount = amount
            transaction.type = CreditTransactionType.TOP_UP
            transaction.chargeOrder = chargeOrder
            transaction.balanceAfter = balanceAfter
            transaction.createdAt = createdAt
            return transaction
        }
    }
}
