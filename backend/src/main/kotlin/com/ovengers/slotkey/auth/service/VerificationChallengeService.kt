
package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.dto.internal.ChallengeIssueResult
import com.ovengers.slotkey.auth.entity.VerificationChallenge
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.mail.VerificationMailSender
import com.ovengers.slotkey.auth.repository.LocalCredentialRepository
import com.ovengers.slotkey.auth.repository.VerificationChallengeRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.repository.MemberRepository
import org.springframework.mail.MailException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class VerificationChallengeService(
    private val guardService: VerificationGuardService,
    private val challengeRepository: VerificationChallengeRepository,
    private val secretGenerator: VerificationSecretGenerator,
    private val secretHasher: VerificationSecretHasher,
    private val mailSender: VerificationMailSender,
    private val memberRepository: MemberRepository,
    private val localCredentialRepository: LocalCredentialRepository,
    private val clock: Clock,
) {
    @Transactional
    fun issue(
        email: String,
        purpose: VerificationPurpose,
    ): ChallengeIssueResult {
        val guard = guardService.acquire(email, purpose)

        // Guard 락 대기가 끝난 시점을 기준으로 시간 측정
        val now = LocalDateTime.now(clock)

        when {
            guard.isAdminLocked ->
                throw BusinessException(ErrorCode.VERIFICATION_ADMIN_LOCKED)

            guard.isBlocked(now) ->
                throw BusinessException(ErrorCode.VERIFICATION_TEMPORARILY_BLOCKED)

            !guard.canSend(now) ->
                throw BusinessException(ErrorCode.VERIFICATION_RESEND_TOO_SOON)
        }

        guard.recordSent(now)

        val guardId =
            requireNotNull(guard.id) {
                "저장된 인증 Guard의 ID가 없습니다."
            }

        // 이전 미인증 Challenge 무효화
        challengeRepository
            .findAllByGuardIdAndVerifiedAtIsNullAndInvalidatedAtIsNull(guardId)
            .forEach { it.invalidate(now) }

        val rawCode = secretGenerator.generateCode()
        val codeHash = secretHasher.hashCode(guardId, rawCode)

        val challenge =
            VerificationChallenge(
                guard,
                codeHash,
                now,
            )

        // 메일 발송 전에 DB 저장 오류 확인
        val saved = challengeRepository.saveAndFlush(challenge)

        // 가입은 발송, 그 외 목적은 로컬 계정이 있을 때만 발송
        val sendMail =
            purpose == VerificationPurpose.SIGNUP ||
                memberRepository
                    .findByEmail(email)
                    .orElse(null)
                    ?.let { member ->
                        localCredentialRepository.existsById(
                            requireNotNull(member.id),
                        )
                    } == true

        if (sendMail) {
            try {
                mailSender.sendCode(email, purpose, rawCode)
            } catch (_: MailException) {
                throw BusinessException(ErrorCode.VERIFICATION_MAIL_FAILED)
            }
        }

        return ChallengeIssueResult(
            requireNotNull(saved.id),
            saved.expiresAt,
            now.plusSeconds(30),
        )
    }
}
