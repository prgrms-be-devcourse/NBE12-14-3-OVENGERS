
package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.dto.internal.LoginResult
import com.ovengers.slotkey.auth.dto.internal.RefreshResult
import com.ovengers.slotkey.auth.repository.LocalCredentialRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtProvider: JwtProvider,
    private val refreshTokenService: RefreshTokenService,
    private val localCredentialRepository: LocalCredentialRepository,
) {
    @Transactional
    fun login(
        email: String,
        password: String,
    ): LoginResult {
        val member =
            memberRepository
                .findByEmailForUpdate(email)
                .orElseThrow { BusinessException(ErrorCode.INVALID_CREDENTIALS) }

        val memberId = requireNotNull(member.id)

        val credential =
            localCredentialRepository
                .findById(memberId)
                .orElseThrow { BusinessException(ErrorCode.INVALID_CREDENTIALS) }

        if (!passwordEncoder.matches(password, credential.passwordHash)) {
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        }

        when (member.status) {
            MemberStatus.WITHDRAWN ->
                throw BusinessException(ErrorCode.ACCOUNT_WITHDRAWN)

            MemberStatus.ACTIVE -> Unit

            else ->
                throw BusinessException(ErrorCode.ACCOUNT_INACTIVE)
        }

        val issuedRefreshToken = refreshTokenService.issueRefreshToken(member)

        val accessToken =
            jwtProvider.genAccessToken(
                member,
                issuedRefreshToken.sessionId,
                issuedRefreshToken.sessionExpiresAt,
            )

        return LoginResult(
            accessToken,
            issuedRefreshToken.rawToken,
            issuedRefreshToken.sessionExpiresAt,
        )
    }

    fun refresh(rawRefreshToken: String): RefreshResult {
        val rotation = refreshTokenService.rotate(rawRefreshToken)

        // 재사용 탐지 시 세션 폐기 트랜잭션이 커밋된 이후 예외 발생
        if (rotation.reused) {
            throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        }

        // 정상 Rotation 결과에만 존재하는 값
        val member = requireNotNull(rotation.member)
        val session = requireNotNull(rotation.session)
        val rawToken = requireNotNull(rotation.rawToken)

        val accessToken =
            jwtProvider.genAccessToken(
                member,
                session.id,
                session.expiresAt,
            )

        return RefreshResult(
            accessToken,
            rawToken,
            session.expiresAt,
        )
    }

    @Transactional
    fun logout(rawRefreshToken: String) {
        refreshTokenService.revokeRefreshToken(rawRefreshToken)
    }
}
