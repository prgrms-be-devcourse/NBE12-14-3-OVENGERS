
package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.service.AuditLogService
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.ADMIN_LOCKED
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.INVALID_CHALLENGE
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.SUCCESS
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.TEMPORARILY_BLOCKED
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.WRONG_CODE
import com.ovengers.slotkey.auth.entity.VerificationGrant
import com.ovengers.slotkey.auth.entity.VerificationGuard
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.repository.VerificationChallengeRepository
import com.ovengers.slotkey.auth.repository.VerificationGrantRepository
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class VerificationCheckService(
    private val guardService: VerificationGuardService,
    private val challengeRepository: VerificationChallengeRepository,
    private val grantRepository: VerificationGrantRepository,
    private val secretGenerator: VerificationSecretGenerator,
    private val secretHasher: VerificationSecretHasher,
    private val clock: Clock,
    private val memberRepository: MemberRepository,
    private val auditLogService: AuditLogService,
    private val authSessionService: AuthSessionService,
) {
    @Transactional
    fun verify(
        email: String,
        purpose: VerificationPurpose,
        challengeId: Long,
        rawCode: String,
    ): VerificationCheckResult {
        val guard = guardService.acquire(email, purpose)
        val now = LocalDateTime.now(clock)

        if (guard.isBlocked(now)) {
            return blockedResult(guard)
        }

        guard.prepareAttempt(now)

        val guardId =
            requireNotNull(guard.id) {
                "인증 Guard의 ID가 없습니다."
            }

        val challenge =
            challengeRepository
                .findFirstByGuardIdOrderByIdDesc(guardId)
                .orElse(null)

        // 가장 최근에 발급된 인증번호만 허용
        if (
            challenge == null ||
            challenge.id != challengeId ||
            !challenge.isUsable(now)
        ) {
            return VerificationCheckResult.failure(
                INVALID_CHALLENGE,
                null,
            )
        }

        if (!secretHasher.matchesCode(guardId, rawCode, challenge.codeHash)) {
            val blocked = guard.recordFailure(now)

            if (blocked) {
                challenge.invalidate(now)

                if (guard.isAdminLocked) {
                    suspendMemberIfNeeded(email)
                }

                return blockedResult(guard)
            }

            return VerificationCheckResult.failure(
                WRONG_CODE,
                null,
            )
        }

        challenge.markVerified(now)
        guard.recordSuccess(now)

        val rawGrant = secretGenerator.generateGrant()

        val grant =
            grantRepository.saveAndFlush(
                VerificationGrant(
                    challenge,
                    secretHasher.hashGrant(rawGrant),
                    now,
                ),
            )

        return VerificationCheckResult(
            SUCCESS,
            rawGrant,
            grant.expiresAt,
            null,
        )
    }

    private fun suspendMemberIfNeeded(email: String) {
        val member =
            memberRepository
                .findByEmailForUpdate(email)
                .orElse(null)
                ?.takeIf { it.status == MemberStatus.ACTIVE }
                ?: return

        val memberId =
            requireNotNull(member.id) {
                "정지 대상 회원의 ID가 없습니다."
            }

        val beforeStatus = member.status

        member.updateStatus(MemberStatus.SUSPENDED)

        authSessionService.revokeAllForMember(
            memberId,
            LocalDateTime.now(clock),
        )

        auditLogService.log(
            null,
            AuditAction.LOCK_MEMBER_BY_VERIFICATION,
            AuditTargetType.MEMBER,
            memberId,
            "이메일 인증 실패 3단계 초과",
            beforeStatus,
            MemberStatus.SUSPENDED,
        )
    }

    private fun blockedResult(guard: VerificationGuard): VerificationCheckResult =
        VerificationCheckResult.failure(
            if (guard.isAdminLocked) ADMIN_LOCKED else TEMPORARILY_BLOCKED,
            guard.blockedUntil,
        )
}
