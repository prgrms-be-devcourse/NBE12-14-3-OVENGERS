
package com.ovengers.slotkey.member.service

import com.ovengers.slotkey.auth.entity.LocalCredential
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.repository.LocalCredentialRepository
import com.ovengers.slotkey.auth.service.VerificationGrantService
import com.ovengers.slotkey.credit.service.CreditGrantService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.dto.response.MemberResponse
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import org.hibernate.exception.ConstraintViolationException
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class MemberService(
    private val memberRepository: MemberRepository,
    private val passwordEncoder: PasswordEncoder,
    private val creditGrantService: CreditGrantService,
    private val localCredentialRepository: LocalCredentialRepository,
    private val clock: Clock,
    private val verificationGrantService: VerificationGrantService,
    @param:Value("\${app.credit.signup-grant}")
    private val signupGrantAmount: Int,
) {
    @Transactional(readOnly = true)
    fun getMyInfo(memberId: Long): Member =
        memberRepository
            .findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.AUTHENTICATION_REQUIRED) }

    @Transactional
    fun signup(
        email: String,
        password: String,
        passwordConfirm: String,
        nickname: String,
        rawGrant: String,
    ): Member {
        if (password != passwordConfirm) {
            throw BusinessException(ErrorCode.PASSWORD_CONFIRM_MISMATCH)
        }

        val grant =
            verificationGrantService.requireUsable(
                email,
                VerificationPurpose.SIGNUP,
                rawGrant,
            )
        val now = LocalDateTime.now(clock)

        if (memberRepository.existsByEmail(email)) {
            throw BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS)
        }

        val passwordHash = passwordEncoder.encode(password)

        val member =
            Member(email, nickname).apply {
                verifyEmail(grant.challenge.verifiedAt)
            }

        val savedMember =
            try {
                memberRepository.saveAndFlush(member)
            } catch (e: DataIntegrityViolationException) {
                val constraintName =
                    (e.cause as? ConstraintViolationException)?.constraintName

                if (constraintName in setOf("email", "member.email")) {
                    throw BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS)
                }
                throw e
            }

        val memberId =
            requireNotNull(savedMember.id) {
                "저장된 회원의 ID가 없습니다."
            }

        localCredentialRepository.save(
            LocalCredential(
                savedMember,
                passwordHash,
                now,
            ),
        )

        // 크레딧 지급 시 영속성 컨텍스트가 clear되기 전에 증표 소비
        grant.consume(now)

        creditGrantService.grantSignupCredit(
            memberId,
            signupGrantAmount,
        )

        return memberRepository
            .findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
    }

    @Transactional
    fun updateNickname(
        memberId: Long,
        nickname: String,
    ): MemberResponse =
        memberRepository
            .findById(memberId)
            .orElseThrow { BusinessException(ErrorCode.AUTHENTICATION_REQUIRED) }
            .apply { updateNickname(nickname) }
            .let(::MemberResponse)
}
