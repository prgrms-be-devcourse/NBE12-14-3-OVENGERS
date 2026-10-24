package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.pg.PgClient
import com.ovengers.slotkey.credit.pg.PgConfirmationResult
import com.ovengers.slotkey.credit.pg.PgPaymentLookupResult
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.idempotency.IdempotencyService
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

@Service
class ChargeOrderConfirmService(
    private val transactionService: ChargeOrderConfirmTransactionService,
    private val pgClient: PgClient,
    private val idempotencyService: IdempotencyService,
) {
    fun confirm(
        memberId: Long?,
        paymentKey: String,
        orderId: String,
        amount: Long,
        idempotencyKey: String?,
    ): ChargeOrderConfirmResponse {
        if (idempotencyKey.isNullOrBlank()) {
            throw BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED)
        }

        val preparation =
            try {
                transactionService.prepare(memberId, paymentKey, orderId, amount, idempotencyKey)
            } catch (exception: DataIntegrityViolationException) {
                if (isClientIdempotencyKeyConflict(exception)) {
                    throw BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED)
                }
                throw exception
            }
        preparation.rejection?.let { throw BusinessException(it) }
        if (!preparation.shouldCallPg()) {
            val currentResponse = requireNotNull(preparation.currentResponse)
            if (currentResponse.status == ChargeOrderStatus.PAID) {
                val stored =
                    idempotencyService.find(
                        idempotencyKey,
                        memberId,
                        ChargeOrderConfirmTransactionService.REQUEST_PATH,
                        ChargeOrderConfirmResponse::class.java,
                    )
                if (stored != null && orderId == stored.orderId) {
                    return stored
                }
            }
            return currentResponse
        }

        val result = pgClient.confirm(requireNotNull(preparation.pgRequest))
        if (result.type == PgConfirmationResult.Type.APPROVED) {
            val approved = completeApprovedOrKeepConfirming(orderId, paymentKey, idempotencyKey)
            recordPaymentMethodQuietly(orderId, result)
            return approved
        }
        if (result.type == PgConfirmationResult.Type.DECLINED) {
            val errorCode = result.errorCode ?: throw NullPointerException("PG 거절 결과에는 오류 코드가 필요합니다.")
            transactionService.completeDeclined(orderId, errorCode)
            throw BusinessException(ErrorCode.PAYMENT_DECLINED)
        }
        if (result.unknownReason == PgConfirmationResult.UnknownReason.ALREADY_PROCESSED) {
            val lookup = pgClient.findByPaymentKey(paymentKey)
            if (lookup.status == PgPaymentLookupResult.Status.APPROVED) {
                return completeApprovedOrKeepConfirming(orderId, paymentKey, idempotencyKey)
            }
            log.warn(
                "PG 승인 결과 확인 조회가 확정적이지 않습니다. orderId={} lookupStatus={}",
                orderId,
                lookup.status,
            )
        }
        if (result.unknownReason == PgConfirmationResult.UnknownReason.MERCHANT_ERROR) {
            throw BusinessException(ErrorCode.PG_CONFIRMATION_ERROR)
        }
        return transactionService.prepare(memberId, paymentKey, orderId, amount, idempotencyKey).currentResponse
            ?: throw BusinessException(ErrorCode.CHARGE_ORDER_STATE_CONFLICT)
    }

    /** 표시용 결제수단 기록. 실패해도 이미 확정된 충전 결과를 바꾸지 않는다. */
    private fun recordPaymentMethodQuietly(
        orderId: String,
        result: PgConfirmationResult,
    ) {
        val paymentMethod = result.paymentMethod ?: return
        try {
            transactionService.recordPaymentMethod(orderId, paymentMethod, result.paymentProvider)
        } catch (exception: RuntimeException) {
            log.warn(
                "결제수단 기록에 실패했습니다. orderId={} exceptionClass={}",
                orderId,
                exception.javaClass.simpleName,
            )
        }
    }

    private fun completeApprovedOrKeepConfirming(
        orderId: String,
        paymentKey: String,
        idempotencyKey: String,
    ): ChargeOrderConfirmResponse =
        try {
            transactionService.completeApproved(orderId, paymentKey, idempotencyKey)
        } catch (exception: RuntimeException) {
            val errorCode =
                if (exception is BusinessException) {
                    exception.errorCode.code
                } else {
                    "N/A"
                }
            log.error(
                "PG 승인 후 DB 2 반영에 실패했습니다. orderId={} exceptionClass={} errorCode={}",
                orderId,
                exception.javaClass.simpleName,
                errorCode,
                exception,
            )
            val current = transactionService.currentResponse(orderId)
            if (current.status == ChargeOrderStatus.PAID || current.status == ChargeOrderStatus.CONFIRMING) {
                current
            } else {
                throw BusinessException(ErrorCode.CHARGE_ORDER_STATE_CONFLICT)
            }
        }

    private fun isClientIdempotencyKeyConflict(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            if (current is ConstraintViolationException) {
                val constraintName = current.constraintName
                if (
                    CLIENT_IDEMPOTENCY_CONSTRAINT == constraintName ||
                    constraintName?.endsWith(".$CLIENT_IDEMPOTENCY_CONSTRAINT") == true
                ) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChargeOrderConfirmService::class.java)
        private const val CLIENT_IDEMPOTENCY_CONSTRAINT = "uk_charge_order_member_client_idempotency"
    }
}
