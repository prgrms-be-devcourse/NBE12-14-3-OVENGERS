
package com.ovengers.slotkey.member.entity

import com.ovengers.slotkey.global.common.entity.BaseTimeEntity
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
@Table(name = "member")
class Member() : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Column(unique = true, nullable = false, length = 254)
    lateinit var email: String
        protected set

    @field:Column(nullable = false, length = 50)
    lateinit var nickname: String
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    lateinit var role: MemberRole
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    lateinit var status: MemberStatus
        protected set

    @field:Column(nullable = false)
    var balance: Int = 0
        protected set

    @field:Column(name = "withdrawn_at")
    var withdrawnAt: LocalDateTime? = null
        protected set

    @field:Column(name = "email_verified_at")
    var emailVerifiedAt: LocalDateTime? = null
        protected set

    constructor(
        email: String,
        nickname: String,
    ) : this() {
        this.email = email
        this.nickname = nickname
        this.role = MemberRole.USER
        this.status = MemberStatus.ACTIVE
    }

    fun updateStatus(status: MemberStatus) {
        this.status = status
    }

    fun updateNickname(nickname: String) {
        this.nickname = nickname
    }

    fun withdraw(withdrawnAt: LocalDateTime?) {
        this.status = MemberStatus.WITHDRAWN
        this.withdrawnAt = withdrawnAt
    }

    fun expireRemainingCredit(): Int {
        val expiredAmount = balance
        balance = 0
        return expiredAmount
    }

    fun addCredits(amount: Int) {
        balance += amount
    }

    fun isEmailVerified(): Boolean = emailVerifiedAt != null

    fun verifyEmail(verifiedAt: LocalDateTime?) {
        requireNotNull(verifiedAt) {
            "인증 시각은 필수입니다."
        }

        if (emailVerifiedAt == null) {
            emailVerifiedAt = verifiedAt
        }
    }
}
