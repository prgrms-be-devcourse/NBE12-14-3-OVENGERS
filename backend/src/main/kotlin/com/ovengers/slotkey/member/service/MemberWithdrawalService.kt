
package com.ovengers.slotkey.member.service

import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository
import com.ovengers.slotkey.auth.repository.LocalCredentialRepository
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.credit.service.CreditExpirationService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class MemberWithdrawalService(
    private val memberRepository: MemberRepository,
    private val reservationRepository: ReservationRepository,
    private val creditExpirationService: CreditExpirationService,
    private val authSessionService: AuthSessionService,
    private val doorAccessTokenRepository: DoorAccessTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val clock: Clock,
    private val localCredentialRepository: LocalCredentialRepository,
) {
    @Transactional
    fun withdraw(
        memberId: Long,
        currentPassword: String,
    ) {
        val member =
            memberRepository
                .findByIdForUpdate(memberId)
                .orElseThrow {
                    BusinessException(ErrorCode.AUTHENTICATION_REQUIRED)
                }

        if (member.role != MemberRole.USER) {
            throw BusinessException(
                ErrorCode.FORBIDDEN_ROLE,
                "관리자 계정은 회원탈퇴 대상이 아닙니다.",
            )
        }

        when (member.status) {
            MemberStatus.WITHDRAWN ->
                throw BusinessException(ErrorCode.ACCOUNT_WITHDRAWN)

            MemberStatus.ACTIVE -> Unit

            else ->
                throw BusinessException(ErrorCode.ACCOUNT_INACTIVE)
        }

        val credential =
            localCredentialRepository
                .findById(memberId)
                .orElseThrow {
                    BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH)
                }

        if (!passwordEncoder.matches(currentPassword, credential.passwordHash)) {
            throw BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH)
        }

        val hasActiveReservation =
            reservationRepository.existsByMemberIdAndStatusIn(
                memberId,
                listOf(
                    ReservationStatus.HELD,
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.IN_USE,
                ),
            )

        if (hasActiveReservation) {
            throw BusinessException(ErrorCode.WITHDRAWAL_ACTIVE_RESERVATION)
        }

        val now = LocalDateTime.now(clock)

        creditExpirationService.expireForWithdrawal(member, now)

        member.withdraw(now)

        authSessionService.revokeAllForMember(memberId, now)

        doorAccessTokenRepository.revokeAllByMemberId(
            memberId,
            now,
            "MEMBER_WITHDRAWAL",
        )
    }
}
