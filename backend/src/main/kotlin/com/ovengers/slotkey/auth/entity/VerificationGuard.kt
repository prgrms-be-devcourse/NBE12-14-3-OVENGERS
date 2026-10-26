
package com.ovengers.slotkey.auth.entity

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
    name = "verification_guard",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_verification_guard_email_purpose",
            columnNames = ["email", "purpose"],
        ),
    ],
)
class VerificationGuard protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Column(nullable = false, length = 254)
    lateinit var email: String
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30)
    lateinit var purpose: VerificationPurpose
        protected set

    @field:Column(name = "failed_attempts", nullable = false)
    var failedAttempts: Int = 0
        protected set

    @field:Column(name = "block_stage", nullable = false)
    var blockStage: Int = 0
        protected set

    @field:Column(name = "blocked_until")
    var blockedUntil: LocalDateTime? = null
        protected set

    @field:Column(name = "admin_locked", nullable = false)
    var isAdminLocked: Boolean = false
        protected set

    @field:Column(name = "last_sent_at")
    var lastSentAt: LocalDateTime? = null
        protected set

    constructor(
        email: String,
        purpose: VerificationPurpose,
    ) : this() {
        this.email = email
        this.purpose = purpose
    }

    fun isBlocked(now: LocalDateTime): Boolean = isAdminLocked || (blockedUntil?.isAfter(now) == true)

    fun prepareAttempt(now: LocalDateTime) {
        check(!isBlocked(now)) {
            "이메일 인증이 차단된 상태입니다."
        }

        if (blockedUntil != null) {
            failedAttempts = 0
            blockedUntil = null
        }
    }

    // 반환값: 이번 오입력으로 차단이 시작됐는가?
    fun recordFailure(now: LocalDateTime): Boolean {
        prepareAttempt(now)
        failedAttempts++

        if (failedAttempts < 5) return false

        blockStage++

        when (blockStage) {
            1 -> blockedUntil = now.plusMinutes(5)
            2 -> blockedUntil = now.plusHours(1)
            else -> {
                isAdminLocked = true
                blockedUntil = null
            }
        }

        return true
    }

    fun recordSuccess(now: LocalDateTime) {
        prepareAttempt(now)
        resetFailureState()
    }

    fun unlockByAdmin() = resetFailureState()

    private fun resetFailureState() {
        failedAttempts = 0
        blockStage = 0
        blockedUntil = null
        isAdminLocked = false
    }

    fun canSend(now: LocalDateTime): Boolean =
        !isBlocked(now) &&
            (lastSentAt?.plusSeconds(30)?.let { !now.isBefore(it) } ?: true)

    fun recordSent(now: LocalDateTime) {
        check(canSend(now)) {
            "인증번호 발송이 차단됐거나 재발송 간격이 지나지 않았습니다."
        }

        prepareAttempt(now)
        lastSentAt = now
    }
}
