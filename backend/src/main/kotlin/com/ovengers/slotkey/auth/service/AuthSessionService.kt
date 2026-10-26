
package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.entity.AuthSession
import com.ovengers.slotkey.auth.repository.AuthSessionRepository
import com.ovengers.slotkey.auth.repository.RefreshTokenRepository
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberStatus
import jakarta.persistence.PersistenceException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class AuthSessionService(
    private val authSessionRepository: AuthSessionRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(member: Member): AuthSession =
        authSessionRepository.save(
            AuthSession(member, LocalDateTime.now(clock)),
        )

    fun validate(
        sessionId: String?,
        memberId: Long,
    ): ValidationResult {
        if (sessionId.isNullOrBlank()) {
            return ValidationResult.INVALID
        }

        return try {
            val now = LocalDateTime.now(clock)

            val usable =
                authSessionRepository.isUsable(
                    sessionId,
                    memberId,
                    now,
                    MemberStatus.ACTIVE,
                )

            when {
                usable -> ValidationResult.VALID

                authSessionRepository
                    .findMemberStatusForAccessAttempt(
                        sessionId,
                        memberId,
                        now,
                    ).filter { it != MemberStatus.ACTIVE }
                    .isPresent -> ValidationResult.MEMBER_INACTIVE

                else -> ValidationResult.INVALID
            }
        } catch (failure: RuntimeException) {
            if (
                failure !is DataAccessException &&
                failure !is PersistenceException &&
                failure !is TransactionException
            ) {
                throw failure
            }

            log.error(
                "Authentication session lookup failed; rejecting request (causeType={})",
                failure.javaClass.simpleName,
            )

            ValidationResult.UNAVAILABLE
        }
    }

    @Transactional
    fun revoke(
        session: AuthSession,
        now: LocalDateTime,
    ) {
        session.revoke(now)
    }

    @Transactional
    fun revokeAllForMember(
        memberId: Long,
        now: LocalDateTime,
    ) {
        authSessionRepository.revokeAllByMemberId(memberId, now)
        refreshTokenRepository.revokeAllByMemberId(memberId, now)
    }

    enum class ValidationResult {
        VALID,
        INVALID,
        MEMBER_INACTIVE,
        UNAVAILABLE,
    }
}
