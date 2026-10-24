package com.ovengers.slotkey.credit.webhook

import com.ovengers.slotkey.credit.entity.ChargeOrder
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.pg.PgClient
import com.ovengers.slotkey.credit.pg.PgPaymentLookupResult
import com.ovengers.slotkey.credit.repository.ChargeOrderRepository
import com.ovengers.slotkey.credit.scheduler.ChargeOrderRecoveryTransactionService
import com.ovengers.slotkey.credit.service.ChargeOrderConfirmTransactionService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 웹훅 수신 행 한 건을 처리한다(docs/decisions/pg-charge.md 5단계 결정 4).
 *
 * 본문의 paymentKey·status는 쓰지 않는다. 주문에 저장된 paymentKey로 PG를 조회하고, 승인일 때만
 * 기존 DB 2([ChargeOrderConfirmTransactionService.completeApproved])를 호출한다
 * ([PgWebhookConfirmTransactionService]가 주문 잠금 아래에서 이미 PAID인지 구분한 뒤 같은 트랜잭션으로 호출).
 * 이 클래스에는 트랜잭션을 두지 않는다: PG 조회는 트랜잭션 밖이고, 충전 1회는 DB 2의 주문 잠금·상태 검사·
 * credit_transaction.charge_order_id UNIQUE가 보장한다. FAILED 전이와 수동 처리 표시는 4단계 복구 스케줄러가 한다.
 */
@Component
class PgWebhookEventProcessor(
    private val eventStore: PgWebhookEventStore,
    private val chargeOrderRepository: ChargeOrderRepository,
    private val webhookConfirmTransactionService: PgWebhookConfirmTransactionService,
    private val recoveryTransactionService: ChargeOrderRecoveryTransactionService,
    private val pgClient: PgClient,
) {
    fun process(eventId: Long?) {
        val orderId = eventStore.findReceivedOrderId(eventId) ?: return
        val result = handle(orderId)
        eventStore.markDone(eventId, result)
        log.info("웹훅을 처리했습니다. orderId={} result={}", orderId, result)
    }

    private fun handle(orderId: String): PgWebhookEventResult {
        val order = chargeOrderRepository.findByOrderId(orderId) ?: return PgWebhookEventResult.ORDER_NOT_FOUND
        return when (order.status) {
            ChargeOrderStatus.PAID -> PgWebhookEventResult.ALREADY_PAID
            ChargeOrderStatus.FAILED -> PgWebhookEventResult.ORDER_FAILED
            ChargeOrderStatus.PENDING -> {
                log.warn("PENDING 주문에 웹훅이 왔습니다. 확정하지 않습니다. orderId={}", orderId)
                PgWebhookEventResult.ORDER_PENDING
            }
            ChargeOrderStatus.CONFIRMING -> handleConfirming(order)
        }
    }

    private fun handleConfirming(order: ChargeOrder): PgWebhookEventResult {
        val paymentKey = order.paymentKey
        val clientIdempotencyKey = order.clientIdempotencyKey
        if (paymentKey.isNullOrBlank() || clientIdempotencyKey.isNullOrBlank()) {
            log.warn(
                "복구 필수값이 없는 CONFIRMING 주문이라 웹훅으로 확정하지 않습니다. orderId={}",
                order.orderId,
            )
            return PgWebhookEventResult.ORDER_INVARIANT_VIOLATION
        }

        val lookup =
            try {
                pgClient.findByPaymentKey(paymentKey)
            } catch (exception: RuntimeException) {
                log.warn(
                    "웹훅 처리 중 PG 조회에 실패했습니다. orderId={} exceptionClass={}",
                    order.orderId,
                    exception.javaClass.simpleName,
                )
                return PgWebhookEventResult.PG_LOOKUP_ERROR
            }

        if (lookup.status != PgPaymentLookupResult.Status.APPROVED) {
            return PgWebhookEventResult.PG_NOT_APPROVED
        }
        if (!matches(order, lookup)) {
            log.warn(
                "웹훅 처리 중 PG 조회 응답이 로컬 주문과 일치하지 않습니다. orderId={}",
                order.orderId,
            )
            return PgWebhookEventResult.PG_MISMATCH
        }

        return try {
            if (
                webhookConfirmTransactionService.completeIfNotPaid(
                    order.orderId,
                    paymentKey,
                    clientIdempotencyKey,
                )
            ) {
                PgWebhookEventResult.CONFIRMED
            } else {
                PgWebhookEventResult.ALREADY_PAID
            }
        } catch (exception: BusinessException) {
            if (exception.errorCode == ErrorCode.CHARGE_ORDER_STATE_CONFLICT) {
                handleStateConflict(order, lookup)
            } else {
                logCompleteError(order, exception)
                PgWebhookEventResult.COMPLETE_ERROR
            }
        } catch (exception: RuntimeException) {
            logCompleteError(order, exception)
            PgWebhookEventResult.COMPLETE_ERROR
        }
    }

    private fun handleStateConflict(
        order: ChargeOrder,
        lookup: PgPaymentLookupResult,
    ): PgWebhookEventResult {
        if (chargeOrderRepository.findByOrderId(order.orderId)?.status == ChargeOrderStatus.PAID) {
            return PgWebhookEventResult.ALREADY_PAID
        }
        recoveryTransactionService.recordApprovedStateConflict(
            order.id,
            lookup.rawStatus ?: throw NullPointerException(),
        )
        return PgWebhookEventResult.STATE_CONFLICT
    }

    /** 4단계 ChargeOrderRecoveryProcessor와 같은 대조 규칙(pg-charge.md 5단계 결정 5). */
    private fun matches(
        order: ChargeOrder,
        lookup: PgPaymentLookupResult,
    ): Boolean =
        order.paymentKey == lookup.paymentKey &&
            order.orderId == lookup.orderId &&
            lookup.totalAmount != null &&
            order.amount == lookup.totalAmount &&
            lookup.currency == "KRW" &&
            !lookup.rawStatus.isNullOrBlank()

    private fun logCompleteError(
        order: ChargeOrder,
        exception: RuntimeException,
    ) {
        log.error(
            "웹훅 처리 중 충전 확정(DB 2)에 실패했습니다. 복구 스케줄러가 다시 확인합니다. " +
                "orderId={} exceptionClass={}",
            order.orderId,
            exception.javaClass.simpleName,
            exception,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(PgWebhookEventProcessor::class.java)
    }
}
