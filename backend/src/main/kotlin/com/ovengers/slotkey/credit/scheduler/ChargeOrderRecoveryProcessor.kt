package com.ovengers.slotkey.credit.scheduler

import com.ovengers.slotkey.credit.pg.PgClient
import com.ovengers.slotkey.credit.pg.PgPaymentLookupResult
import com.ovengers.slotkey.credit.service.ChargeOrderConfirmTransactionService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class ChargeOrderRecoveryProcessor(
    private val recoveryTransactionService: ChargeOrderRecoveryTransactionService,
    private val confirmTransactionService: ChargeOrderConfirmTransactionService,
    private val pgClient: PgClient,
) {
    fun recover(
        chargeOrderId: Long?,
        now: LocalDateTime,
    ) {
        val target = recoveryTransactionService.prepare(chargeOrderId, now) ?: return
        var result: PgPaymentLookupResult? = null
        try {
            result = pgClient.findByPaymentKey(target.paymentKey)
            if (result.paymentObjectReceived && !matches(target, result)) {
                recoveryTransactionService.markManualReview(chargeOrderId, now, "PG_PAYMENT_MISMATCH")
                return
            }
            when (result.status) {
                PgPaymentLookupResult.Status.APPROVED ->
                    confirmTransactionService.completeApproved(
                        target.orderId,
                        target.paymentKey,
                        target.clientIdempotencyKey,
                    )
                PgPaymentLookupResult.Status.FAILED -> completeFailed(target, result, now)
                PgPaymentLookupResult.Status.UNKNOWN ->
                    recoveryTransactionService.reschedule(chargeOrderId, now.plusMinutes(1))
            }
        } catch (exception: BusinessException) {
            if (isApprovedStateConflict(result, exception)) {
                recoveryTransactionService.recordApprovedStateConflict(
                    chargeOrderId,
                    result?.rawStatus ?: throw NullPointerException(),
                )
                return
            }
            recoveryTransactionService.reschedule(chargeOrderId, now.plusMinutes(1))
            throw exception
        } catch (exception: RuntimeException) {
            recoveryTransactionService.reschedule(chargeOrderId, now.plusMinutes(1))
            throw exception
        }
    }

    fun markExpired(
        chargeOrderId: Long?,
        now: LocalDateTime,
    ) {
        recoveryTransactionService.markManualReview(
            chargeOrderId,
            now,
            "RECOVERY_WINDOW_EXCEEDED",
        )
    }

    private fun completeFailed(
        target: ChargeOrderRecoveryTarget,
        result: PgPaymentLookupResult,
        now: LocalDateTime,
    ) {
        val rawStatus = result.rawStatus
        if (rawStatus.isNullOrBlank()) {
            recoveryTransactionService.markManualReview(
                target.chargeOrderId,
                now,
                "PG_STATUS_MISSING",
            )
            return
        }
        recoveryTransactionService.markFailed(
            target.chargeOrderId,
            "PG_STATUS_$rawStatus",
            now,
        )
    }

    private fun matches(
        target: ChargeOrderRecoveryTarget,
        result: PgPaymentLookupResult,
    ): Boolean {
        val matches =
            target.paymentKey == result.paymentKey &&
                target.orderId == result.orderId &&
                result.totalAmount != null &&
                target.amount == result.totalAmount &&
                result.currency == "KRW" &&
                !result.rawStatus.isNullOrBlank()
        if (!matches) {
            log.warn(
                "PG 조회 응답이 로컬 주문과 일치하지 않습니다. orderId={} lookupStatus={}",
                target.orderId,
                result.status,
            )
        }
        return matches
    }

    private fun isApprovedStateConflict(
        result: PgPaymentLookupResult?,
        exception: BusinessException,
    ): Boolean =
        result?.status == PgPaymentLookupResult.Status.APPROVED &&
            exception.errorCode == ErrorCode.CHARGE_ORDER_STATE_CONFLICT

    companion object {
        private val log = LoggerFactory.getLogger(ChargeOrderRecoveryProcessor::class.java)
    }
}
