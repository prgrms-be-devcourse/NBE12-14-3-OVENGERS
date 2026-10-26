
package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.repository.LocalCredentialRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.repository.MemberRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class PasswordResetService(
    private val verificationGrantService: VerificationGrantService,
    private val memberRepository: MemberRepository,
    private val localCredentialRepository: LocalCredentialRepository,
    private val passwordEncoder: PasswordEncoder,
    private val clock: Clock,
    private val authSessionService: AuthSessionService,
) {
    @Transactional
    fun reset(
        email: String,
        rawGrant: String?,
        password: String,
        passwordConfirm: String,
    ) {
        if (password != passwordConfirm) {
            throw BusinessException(ErrorCode.PASSWORD_CONFIRM_MISMATCH)
        }

        // Guard 락 획득 및 인증 Grant 검증
        val grant =
            verificationGrantService.requireUsable(
                email,
                VerificationPurpose.PASSWORD_RESET,
                rawGrant,
            )

        // Guard 이후 Member 락 획득
        val member =
            memberRepository
                .findByEmailForUpdate(email)
                .orElseThrow {
                    BusinessException(ErrorCode.VERIFICATION_GRANT_INVALID)
                }

        val memberId =
            requireNotNull(member.id) {
                "비밀번호 재설정 대상 회원의 ID가 없습니다."
            }

        val credential =
            localCredentialRepository
                .findById(memberId)
                .orElseThrow {
                    BusinessException(ErrorCode.VERIFICATION_GRANT_INVALID)
                }

        val now = LocalDateTime.now(clock)

        // 비밀번호 변경 및 일회용 Grant 소비
        credential.changePassword(
            passwordEncoder.encode(password),
            now,
        )
        grant.consume(now)

        // 기존 로그인 세션 및 Refresh Token 전체 폐기
        authSessionService.revokeAllForMember(memberId, now)
    }
}
