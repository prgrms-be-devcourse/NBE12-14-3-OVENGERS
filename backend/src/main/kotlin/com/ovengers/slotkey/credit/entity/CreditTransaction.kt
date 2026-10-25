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
    // 크래딧 거래 이력 PK
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    final var id: Long? = null
        private set

    // 크레딧 거래가 발생한 회원
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "member_id", nullable = false)
    final lateinit var member: Member
        private set

    // 거래에서 지급, 차감되는 크레딧 금액
    @field:Column(nullable = false)
    final var amount: Int = 0
        private set

    // 크레딧 거래 유형
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30)
    final lateinit var type: CreditTransactionType
        private set

    // 크레딧 거래와 관련된 예약
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "reservation_id")
    final var reservation: Reservation? = null
        private set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "charge_order_id")
    final var chargeOrder: ChargeOrder? = null
        private set

    // 해당 거래 처리 후 회원의 크레딧 잔액
    @field:Column(name = "balance_after", nullable = false)
    final var balanceAfter: Int = 0
        private set

    // 크레딧 거래 사유
    @field:Column(length = 500)
    final var reason: String? = null
        private set

    // 크레딧 거래가 발생한 시각
    @field:Column(name = "created_at", nullable = false)
    final lateinit var createdAt: LocalDateTime
        private set

    // Lombok @NoArgsConstructor(access = AccessLevel.PROTECTED) 대체
    protected constructor()

    // 크레딧 거래 이력 생성
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

    // Lombok @Getter 대체
}
