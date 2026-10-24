package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.service.AuditLogService
import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse
import com.ovengers.slotkey.credit.entity.ChargeOrder
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.pg.PgConfirmRequest
import com.ovengers.slotkey.credit.repository.ChargeOrderRepository
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.idempotency.IdempotencyService
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Service
class ChargeOrderConfirmTransactionService(
    private val chargeOrderRepository: ChargeOrderRepository,
    private val memberRepository: MemberRepository,
    private val creditTransactionRepository: CreditTransactionRepository,
    private val idempotencyService: IdempotencyService,
    private val auditLogService: AuditLogService,
    private val clock: Clock,
) {
    @Transactional
    fun prepare(
        memberId: Long?,
        paymentKey: String,
        orderId: String,
        amount: Long,
        clientIdempotencyKey: String,
    ): ChargeOrderPreparation {
        val order =
            chargeOrderRepository.findByOrderId(orderId)
                ?: throw BusinessException(ErrorCode.CHARGE_ORDER_NOT_FOUND)

        validateRequester(order, memberId)?.let { return ChargeOrderPreparation.reject(it) }

        val amountMismatch = order.amount != amount
        if (amountMismatch) {
            auditLogService.log(
                memberId,
                AuditAction.CHARGE_AMOUNT_MISMATCH,
                AuditTargetType.CHARGE_ORDER,
                order.id,
                "충전 승인 요청 금액 불일치",
                mapOf("storedAmount" to order.amount),
                mapOf("requestedAmount" to amount),
            )
        }

        validateRequestDetails(memberId, orderId, clientIdempotencyKey, amountMismatch)
            ?.let { return ChargeOrderPreparation.reject(it) }

        currentState(order, paymentKey)?.let { return it }

        val pgIdempotencyKey = UUID.randomUUID().toString()
        val confirmingAt = LocalDateTime.now(clock)
        val updated =
            chargeOrderRepository.startConfirming(
                order.id,
                ChargeOrderStatus.PENDING,
                ChargeOrderStatus.CONFIRMING,
                paymentKey,
                clientIdempotencyKey,
                pgIdempotencyKey,
                confirmingAt,
                confirmingAt.plusMinutes(1),
            )
        if (updated == 1) {
            return ChargeOrderPreparation.callPg(
                PgConfirmRequest(paymentKey, orderId, amount, pgIdempotencyKey),
            )
        }

        val concurrent =
            chargeOrderRepository.findByOrderIdForUpdate(orderId)
                ?: throw BusinessException(ErrorCode.CHARGE_ORDER_NOT_FOUND)
        return currentState(concurrent, paymentKey)
            ?: throw BusinessException(ErrorCode.CHARGE_ORDER_STATE_CONFLICT)
    }

    @Transactional
    fun completeApproved(
        orderId: String,
        paymentKey: String,
        clientIdempotencyKey: String,
    ): ChargeOrderConfirmResponse {
        val order =
            chargeOrderRepository.findByOrderIdForUpdate(orderId)
                ?: throw BusinessException(ErrorCode.CHARGE_ORDER_NOT_FOUND)

        if (order.status == ChargeOrderStatus.PAID && paymentKey == order.paymentKey) {
            return ChargeOrderConfirmResponse.from(order)
        }
        if (order.status != ChargeOrderStatus.CONFIRMING || paymentKey != order.paymentKey) {
            throw BusinessException(ErrorCode.CHARGE_ORDER_STATE_CONFLICT)
        }

        val member =
            memberRepository.findByIdForUpdate(order.memberId).orElseThrow {
                BusinessException(ErrorCode.MEMBER_NOT_FOUND)
            }
        val amount = Math.toIntExact(order.amount)
        if (member.balance.toLong() + amount > Int.MAX_VALUE) {
            throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "크레딧 잔액이 허용 범위를 초과합니다.",
            )
        }

        member.addCredits(amount)
        val now = LocalDateTime.now(clock)
        creditTransactionRepository.save(
            CreditTransaction.topUp(
                member,
                amount,
                order,
                member.balance,
                now,
            ),
        )
        order.markPaid(now)

        val response = ChargeOrderConfirmResponse.from(order)
        idempotencyService.save(
            clientIdempotencyKey,
            order.memberId,
            REQUEST_PATH,
            200,
            response,
        )
        return response
    }

    /**
     * PG가 알려 준 결제수단을 표시용으로 기록한다. 충전 확정(completeApproved)과 별도 트랜잭션이라
     * 이 기록이 실패해도 크레딧 충전에는 영향이 없다.
     */
    @Transactional
    fun recordPaymentMethod(
        orderId: String,
        paymentMethod: String,
        paymentProvider: String?,
    ) {
        chargeOrderRepository.findByOrderId(orderId)?.recordPaymentMethod(paymentMethod, paymentProvider)
    }

    @Transactional
    fun completeDeclined(
        orderId: String,
        errorCode: String,
    ) {
        val order =
            chargeOrderRepository.findByOrderIdForUpdate(orderId)
                ?: throw BusinessException(ErrorCode.CHARGE_ORDER_NOT_FOUND)
        if (order.status == ChargeOrderStatus.CONFIRMING) {
            order.markFailed(errorCode, LocalDateTime.now(clock))
        }
    }

    @Transactional(readOnly = true)
    fun currentResponse(orderId: String): ChargeOrderConfirmResponse {
        val order =
            chargeOrderRepository.findByOrderId(orderId)
                ?: throw BusinessException(ErrorCode.CHARGE_ORDER_NOT_FOUND)
        return ChargeOrderConfirmResponse.from(order)
    }

    private fun currentState(
        order: ChargeOrder,
        paymentKey: String,
    ): ChargeOrderPreparation? =
        when {
            order.status == ChargeOrderStatus.PENDING -> null
            order.status == ChargeOrderStatus.PAID && paymentKey == order.paymentKey ->
                ChargeOrderPreparation.current(ChargeOrderConfirmResponse.from(order))
            order.status == ChargeOrderStatus.CONFIRMING ->
                ChargeOrderPreparation.current(ChargeOrderConfirmResponse.from(order))
            else -> throw BusinessException(ErrorCode.CHARGE_ORDER_STATE_CONFLICT)
        }

    private fun validateRequester(
        order: ChargeOrder,
        memberId: Long?,
    ): ErrorCode? {
        if (order.memberId != memberId) {
            return ErrorCode.FORBIDDEN_NOT_OWNER
        }
        val member = memberRepository.findById(memberId).orElse(null) ?: return ErrorCode.AUTHENTICATION_REQUIRED
        return if (member.status == MemberStatus.ACTIVE) null else ErrorCode.ACCOUNT_INACTIVE
    }

    private fun validateRequestDetails(
        memberId: Long?,
        orderId: String,
        clientIdempotencyKey: String,
        amountMismatch: Boolean,
    ): ErrorCode? {
        val claimed = chargeOrderRepository.findByMemberIdAndClientIdempotencyKey(memberId, clientIdempotencyKey)
        if (claimed != null && claimed.orderId != orderId) {
            return ErrorCode.IDEMPOTENCY_KEY_REUSED
        }
        return if (amountMismatch) ErrorCode.CHARGE_ORDER_AMOUNT_MISMATCH else null
    }

    companion object {
        const val REQUEST_PATH = "/credits/charge-orders/confirm"
    }
}
