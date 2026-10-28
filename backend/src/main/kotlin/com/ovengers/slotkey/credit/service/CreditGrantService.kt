package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.dto.response.CreditGrantHistoryResponse
import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.credit.repository.CreditBalanceRepository
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class CreditGrantService(
    private val creditBalanceRepository: CreditBalanceRepository,
    private val creditTransactionRepository: CreditTransactionRepository,
    private val memberRepository: MemberRepository,
    private val clock: Clock,
) {
    // 회원가입 크레딧 지급
    @Transactional
    fun grantSignupCredit(
        memberId: Long?,
        amount: Int,
    ): Int {
        // 지급 금액 검증
        validateAmount(amount)

        // 지급 대상 회원 조회
        val member = findMember(memberId)

        // 지급 후 잔액 검증
        validateBalanceAfterGrant(
            member.balance,
            amount,
        )

        // 회원 잔액 증가
        increaseBalance(memberId, amount)

        // 증가된 잔액을 가진 회원 조회
        val updatedMember = findMember(memberId)

        // 회원가입 크레딧 지급 내역 저장
        saveGrantTransaction(
            updatedMember,
            amount,
            CreditTransactionType.SIGNUP_GRANT,
            null,
        )

        // 지급 후 잔액 반환
        return updatedMember.balance
    }

    // 관리자 크레딧 지급
    @Transactional
    fun grantAdminCredit(
        memberId: Long?,
        amount: Int,
        reason: String?,
    ): Int {
        // 지급 금액 검증
        validateAmount(amount)

        // 관리자 지급 사유 검증
        validateReason(reason)

        // 지급 대상 회원 조회
        val member = findMember(memberId)

        // 지급 후 잔액 검증
        validateBalanceAfterGrant(
            member.balance,
            amount,
        )

        // 회원 잔액 증가
        increaseBalance(memberId, amount)

        // 증가된 잔액을 가진 회원 조회
        val updatedMember = findMember(memberId)

        // 관리자 크레딧 지급 내역 저장
        saveGrantTransaction(
            updatedMember,
            amount,
            CreditTransactionType.ADMIN_GRANT,
            reason,
        )

        // 지급 후 잔액 반환
        return updatedMember.balance
    }

    // 내 관리자 지급 내역 조회(최신순)
    @Transactional(readOnly = true)
    fun getMyAdminGrants(
        memberId: Long?,
        pageable: Pageable,
    ): Page<CreditGrantHistoryResponse> =
        creditTransactionRepository
            .findByMember_IdAndTypeOrderByIdDesc(memberId, CreditTransactionType.ADMIN_GRANT, pageable)
            .map(CreditGrantHistoryResponse::from)

    // 회원 잔액 증가
    private fun increaseBalance(
        memberId: Long?,
        amount: Int,
    ) {
        val updatedRows =
            creditBalanceRepository.increase(memberId, amount)

        // 해당 회원이 없어서 UPDATE되지 않은 경우
        if (updatedRows == 0) {
            throw BusinessException(
                ErrorCode.MEMBER_NOT_FOUND,
            )
        }
    }

    // ID로 회원 조회
    private fun findMember(memberId: Long?): Member =
        memberRepository
            .findById(requireNotNull(memberId))
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

    // 크레딧 지급 거래 내역 저장
    private fun saveGrantTransaction(
        member: Member,
        amount: Int,
        type: CreditTransactionType,
        reason: String?,
    ) {
        val transaction =
            CreditTransaction(
                member,
                amount,
                type,
                null,
                member.balance,
                reason,
                LocalDateTime.now(clock),
            )

        creditTransactionRepository.save(transaction)
    }

    // 지급 금액 검증
    private fun validateAmount(amount: Int) {
        if (amount <= 0) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "크레딧 금액은 0보다 커야 합니다.",
            )
        }
    }

    // 지급 후 잔액 검증
    private fun validateBalanceAfterGrant(
        currentBalance: Int,
        amount: Int,
    ) {
        val balanceAfter =
            currentBalance.toLong() + amount

        if (balanceAfter > Int.MAX_VALUE) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "크레딧 잔액이 허용 범위를 초과합니다.",
            )
        }
    }

    // 관리자 지급 사유 검증
    private fun validateReason(reason: String?) {
        if (reason == null || reason.isBlank()) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "관리자 크레딧 지급 사유는 필수입니다.",
            )
        }

        if (reason.length > 500) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "관리자 크레딧 지급 사유는 500자 이하여야 합니다.",
            )
        }
    }
}
