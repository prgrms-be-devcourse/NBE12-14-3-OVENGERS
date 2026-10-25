package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.credit.repository.CreditBalanceRepository
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class CreditServiceImpl(
    private val creditTransactionRepository: CreditTransactionRepository,
    private val creditBalanceRepository: CreditBalanceRepository,
    private val memberRepository: MemberRepository,
    private val reservationRepository: ReservationRepository,
    private val clock: Clock,
) : CreditService {
    // 예약 결제 크레딧 차감
    @Transactional
    override fun charge(
        memberId: Long?,
        reservationId: Long?,
        amount: Int,
    ): Int {
        validateAmount(amount)

        // 잔액이 충분한 경우에만 차감
        val updatedRows =
            creditBalanceRepository.decreaseIfEnough(memberId, amount)

        if (updatedRows == 0) {
            throw BusinessException(
                ErrorCode.INSUFFICIENT_BALANCE,
            )
        }

        // ID로 회원 / 예약 조회
        val member = findMember(memberId)
        val reservation = findReservation(reservationId)

        saveTransaction(
            member,
            -amount,
            CreditTransactionType.RESERVATION_CHARGE,
            reservation,
        )

        return member.balance
    }

    // 예약 취소 크레딧 환급
    @Transactional
    override fun refund(
        memberId: Long?,
        reservationId: Long?,
        amount: Int,
    ): Int {
        validateAmount(amount)

        // 환불 전 회원 조회
        val member = findMember(memberId)

        // 환불 후 잔액 검증
        validateBalanceAfterRefund(
            member.balance,
            amount,
        )

        // 잔액 증가
        val updatedRows =
            creditBalanceRepository.increase(memberId, amount)

        if (updatedRows == 0) {
            throw BusinessException(
                ErrorCode.MEMBER_NOT_FOUND,
            )
        }

        // 증가된 잔액을 가진 회원 / 예약 조회
        val updatedMember = findMember(memberId)
        val reservation = findReservation(reservationId)

        saveTransaction(
            updatedMember,
            amount,
            CreditTransactionType.REFUND,
            reservation,
        )

        return updatedMember.balance
    }

    // 예약 취소 위약금 차감
    @Transactional
    override fun penalize(
        memberId: Long?,
        reservationId: Long?,
        amount: Int,
    ): Int {
        validateAmount(amount)

        // 잔액이 충분한 경우에만 차감
        val updatedRows =
            creditBalanceRepository.decreaseIfEnough(memberId, amount)

        if (updatedRows == 0) {
            throw BusinessException(
                ErrorCode.INSUFFICIENT_BALANCE,
            )
        }

        // ID로 회원 / 예약 조회
        val member = findMember(memberId)
        val reservation = findReservation(reservationId)

        saveTransaction(
            member,
            -amount,
            CreditTransactionType.PENALTY,
            reservation,
        )

        return member.balance
    }

    // 크레딧 거래 이력 저장
    private fun saveTransaction(
        member: Member,
        amount: Int,
        type: CreditTransactionType,
        reservation: Reservation,
    ) {
        val transaction =
            CreditTransaction(
                member,
                amount,
                type,
                reservation,
                member.balance,
                null,
                LocalDateTime.now(clock),
            )

        creditTransactionRepository.save(transaction)
    }

    // ID로 회원 조회
    private fun findMember(memberId: Long?): Member =
        memberRepository
            .findById(requireNotNull(memberId))
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

    // ID로 예약 조회
    private fun findReservation(reservationId: Long?): Reservation =
        reservationRepository
            .findById(requireNotNull(reservationId))
            .orElseThrow { BusinessException(ErrorCode.RESERVATION_NOT_FOUND) }

    // 크레딧 금액은 0보다 큰 값만 허용
    private fun validateAmount(amount: Int) {
        if (amount <= 0) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "크레딧 금액은 0보다 커야 합니다.",
            )
        }
    }

    // 환불 후 잔액 검증
    private fun validateBalanceAfterRefund(
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
}
