package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.dto.response.ChargeOrderHistoryResponse
import com.ovengers.slotkey.credit.dto.response.ChargeOrderResponse
import com.ovengers.slotkey.credit.entity.ChargeOrder
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.repository.ChargeOrderRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

@Service
class ChargeOrderService(
    private val chargeOrderRepository: ChargeOrderRepository,
    private val memberRepository: MemberRepository,
    private val clock: Clock,
) {
    @Transactional
    fun create(
        memberId: Long?,
        amount: Long,
    ): ChargeOrderResponse {
        validateAmount(amount)
        validateActiveMember(memberId)

        val chargeOrder =
            ChargeOrder.createPending(
                memberId,
                generateOrderId(),
                amount,
                LocalDateTime.now(clock),
            )
        return ChargeOrderResponse.from(chargeOrderRepository.save(chargeOrder))
    }

    @Transactional(readOnly = true)
    fun get(
        memberId: Long?,
        orderId: String,
    ): ChargeOrderResponse {
        val chargeOrder =
            chargeOrderRepository.findByOrderId(orderId)
                ?: throw BusinessException(ErrorCode.CHARGE_ORDER_NOT_FOUND)
        if (chargeOrder.memberId != memberId) {
            throw BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER)
        }
        return ChargeOrderResponse.from(chargeOrder)
    }

    /** 내 충전 내역. 결제가 시도되지 않은 PENDING 주문은 노출하지 않는다. */
    @Transactional(readOnly = true)
    fun getMyHistory(
        memberId: Long?,
        pageable: Pageable,
    ): Page<ChargeOrderHistoryResponse> =
        chargeOrderRepository
            .findByMemberIdAndStatusInOrderByIdDesc(
                memberId,
                setOf(ChargeOrderStatus.PAID, ChargeOrderStatus.CONFIRMING, ChargeOrderStatus.FAILED),
                pageable,
            ).map(ChargeOrderHistoryResponse::from)

    private fun validateActiveMember(memberId: Long?) {
        val requiredMemberId = requireNotNull(memberId)
        val member =
            memberRepository.findById(requiredMemberId).orElseThrow {
                BusinessException(ErrorCode.AUTHENTICATION_REQUIRED)
            }
        if (member.status != MemberStatus.ACTIVE) {
            throw BusinessException(ErrorCode.ACCOUNT_INACTIVE)
        }
    }

    private fun validateAmount(amount: Long) {
        if (amount < MIN_AMOUNT || amount > MAX_AMOUNT || amount % AMOUNT_UNIT != 0L) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "충전 금액은 1,000원 이상 100,000원 이하의 100원 단위여야 합니다.",
            )
        }
    }

    private fun generateOrderId(): String = "chg_" + UUID.randomUUID().toString().lowercase(Locale.ROOT)

    companion object {
        private const val MIN_AMOUNT = 1_000L
        private const val MAX_AMOUNT = 100_000L
        private const val AMOUNT_UNIT = 100L
    }
}
