
package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.dto.internal.IssuedRefreshToken
import com.ovengers.slotkey.auth.entity.AuthSession
import com.ovengers.slotkey.auth.entity.RefreshToken
import com.ovengers.slotkey.auth.repository.AuthSessionRepository
import com.ovengers.slotkey.auth.repository.RefreshTokenRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Clock
import java.time.LocalDateTime
import java.util.HexFormat
import java.util.UUID

@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val authSessionRepository: AuthSessionRepository,
    private val memberRepository: MemberRepository,
    private val entityManager: EntityManager,
    private val clock: Clock,
) {
    @Transactional
    fun issueRefreshToken(member: Member): IssuedRefreshToken {
        val session =
            authSessionRepository.save(
                AuthSession(member, LocalDateTime.now(clock)),
            )

        val rawToken = generateRefreshToken()

        refreshTokenRepository.save(
            RefreshToken(
                member,
                session,
                hashRefreshToken(rawToken),
                session.expiresAt,
            ),
        )

        return IssuedRefreshToken(
            rawToken,
            session.id,
            session.expiresAt,
        )
    }

    /**
     * 락 획득 순서: Member -> AuthSession -> RefreshToken.
     *
     * 재사용 탐지 시 예외 대신 결과를 반환하여 세션 폐기 트랜잭션을
     * 먼저 커밋하고, 호출자가 이후 INVALID_REFRESH_TOKEN으로 처리한다.
     */
    @Transactional
    fun rotate(rawToken: String?): RotationResult {
        if (rawToken.isNullOrBlank()) {
            throw invalidRefreshToken()
        }

        val tokenHash = hashRefreshToken(rawToken)

        val reference =
            refreshTokenRepository
                .findReferenceByTokenHash(tokenHash)
                .orElseThrow(::invalidRefreshToken)

        // 동일한 잠금 순서를 유지하여 경합 및 데드락 위험 감소
        val member =
            memberRepository
                .findByIdForUpdate(reference.getMemberId())
                .orElseThrow(::invalidRefreshToken)

        val session =
            authSessionRepository
                .findByIdForUpdate(reference.getSessionId())
                .orElseThrow(::invalidRefreshToken)

        val refreshToken =
            refreshTokenRepository
                .findByIdForUpdate(reference.getId())
                .orElseThrow(::invalidRefreshToken)

        val now = LocalDateTime.now(clock)

        if (
            member.id != refreshToken.member.id ||
            session.id != refreshToken.authSession.id
        ) {
            throw invalidRefreshToken()
        }

        // 이미 소비된 RT의 재사용 탐지
        if (
            refreshToken.isConsumed() &&
            session.isActiveAt(now) &&
            !refreshToken.isExpired(now)
        ) {
            session.revoke(now)
            refreshTokenRepository.revokeAllBySessionId(session.id, now)

            return RotationResult.reusedResult()
        }

        if (
            refreshToken.isExpired(now) ||
            refreshToken.isRevoked() ||
            !session.isActiveAt(now)
        ) {
            throw invalidRefreshToken()
        }

        when (member.status) {
            MemberStatus.WITHDRAWN ->
                throw BusinessException(ErrorCode.ACCOUNT_WITHDRAWN)

            MemberStatus.ACTIVE -> Unit

            else ->
                throw BusinessException(ErrorCode.ACCOUNT_INACTIVE)
        }

        refreshToken.consume(now)

        val replacement = generateRefreshToken()

        refreshTokenRepository.saveAndFlush(
            RefreshToken(
                member,
                session,
                hashRefreshToken(replacement),
                session.expiresAt,
            ),
        )

        return RotationResult.rotated(replacement, member, session)
    }

    @Transactional
    fun revokeRefreshToken(rawToken: String?) {
        if (rawToken.isNullOrBlank()) return

        val tokenHash = hashRefreshToken(rawToken)

        refreshTokenRepository
            .findWithSessionByTokenHash(tokenHash)
            .ifPresent { refreshToken ->
                val now = LocalDateTime.now(clock)
                val session = refreshToken.authSession

                entityManager.refresh(session, LockModeType.PESSIMISTIC_WRITE)

                session.revoke(now)
                refreshTokenRepository.revokeAllBySessionId(session.id, now)
            }
    }

    private fun generateRefreshToken(): String = UUID.randomUUID().toString()

    private fun hashRefreshToken(rawToken: String): String =
        try {
            val digest = MessageDigest.getInstance("SHA-256")

            HexFormat.of().formatHex(
                digest.digest(rawToken.toByteArray(StandardCharsets.UTF_8)),
            )
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256을 사용할 수 없습니다.", e)
        }

    private fun invalidRefreshToken(): BusinessException = BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)

    @JvmRecord
    data class RotationResult(
        val reused: Boolean,
        val rawToken: String?,
        val member: Member?,
        val session: AuthSession?,
    ) {
        companion object {
            @JvmStatic
            fun reusedResult(): RotationResult = RotationResult(true, null, null, null)

            @JvmStatic
            fun rotated(
                rawToken: String,
                member: Member,
                session: AuthSession,
            ): RotationResult = RotationResult(false, rawToken, member, session)
        }
    }
}
