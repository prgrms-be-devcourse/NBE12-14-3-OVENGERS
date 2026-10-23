package com.ovengers.slotkey.credit.webhook;

import com.ovengers.slotkey.credit.entity.ChargeOrder;
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus;
import com.ovengers.slotkey.credit.pg.PgClient;
import com.ovengers.slotkey.credit.pg.PgPaymentLookupResult;
import com.ovengers.slotkey.credit.repository.ChargeOrderRepository;
import com.ovengers.slotkey.credit.scheduler.ChargeOrderRecoveryTransactionService;
import com.ovengers.slotkey.credit.service.ChargeOrderConfirmTransactionService;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


/**
 * 웹훅 수신 행 한 건을 처리한다(docs/decisions/pg-charge.md 5단계 결정 4).
 *
 * 본문의 paymentKey·status는 쓰지 않는다. 주문에 저장된 paymentKey로 PG를 조회하고, 승인일 때만
 * 기존 DB 2({@link ChargeOrderConfirmTransactionService#completeApproved})를 호출한다
 * ({@link PgWebhookConfirmTransactionService}가 주문 잠금 아래에서 이미 PAID인지 구분한 뒤 같은 트랜잭션으로 호출).
 * 이 클래스에는 트랜잭션을 두지 않는다: PG 조회는 트랜잭션 밖이고, 충전 1회는 DB 2의 주문 잠금·상태 검사·
 * credit_transaction.charge_order_id UNIQUE가 보장한다. FAILED 전이와 수동 처리 표시는 4단계 복구 스케줄러가 한다.
 */
@Component
public class PgWebhookEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(PgWebhookEventProcessor.class);

    private final PgWebhookEventStore eventStore;
    private final ChargeOrderRepository chargeOrderRepository;
    private final PgWebhookConfirmTransactionService webhookConfirmTransactionService;
    private final ChargeOrderRecoveryTransactionService recoveryTransactionService;
    private final PgClient pgClient;

    public PgWebhookEventProcessor(
            PgWebhookEventStore eventStore,
            ChargeOrderRepository chargeOrderRepository,
            PgWebhookConfirmTransactionService webhookConfirmTransactionService,
            ChargeOrderRecoveryTransactionService recoveryTransactionService,
            PgClient pgClient
    ) {
        this.eventStore = eventStore;
        this.chargeOrderRepository = chargeOrderRepository;
        this.webhookConfirmTransactionService = webhookConfirmTransactionService;
        this.recoveryTransactionService = recoveryTransactionService;
        this.pgClient = pgClient;
    }

    public void process(Long eventId) {
        String orderId = eventStore.findReceivedOrderId(eventId);
        if (orderId == null) {
            return;
        }
        PgWebhookEventResult result = handle(orderId);
        eventStore.markDone(eventId, result);
        log.info(
                "웹훅을 처리했습니다. orderId={} result={}",
                orderId,
                result
        );
    }

    private PgWebhookEventResult handle(String orderId) {
        ChargeOrder order = chargeOrderRepository.findByOrderId(orderId).orElse(null);
        if (order == null) {
            return PgWebhookEventResult.ORDER_NOT_FOUND;
        }
        switch (order.getStatus()) {
            case PAID -> {
                return PgWebhookEventResult.ALREADY_PAID;
            }
            case FAILED -> {
                return PgWebhookEventResult.ORDER_FAILED;
            }
            case PENDING -> {
                log.warn(
                        "PENDING 주문에 웹훅이 왔습니다. 확정하지 않습니다. orderId={}",
                        orderId
                );
                return PgWebhookEventResult.ORDER_PENDING;
            }
            case CONFIRMING -> {
                return handleConfirming(order);
            }
            default -> throw new IllegalStateException("Unexpected status: " + order.getStatus());
        }
    }

    private PgWebhookEventResult handleConfirming(ChargeOrder order) {
        String paymentKey = order.getPaymentKey();
        String clientIdempotencyKey = order.getClientIdempotencyKey();
        if (isBlank(paymentKey) || isBlank(clientIdempotencyKey)) {
            log.warn(
                    "복구 필수값이 없는 CONFIRMING 주문이라 웹훅으로 확정하지 않습니다. orderId={}",
                    order.getOrderId()
            );
            return PgWebhookEventResult.ORDER_INVARIANT_VIOLATION;
        }

        PgPaymentLookupResult lookup;
        try {
            lookup = pgClient.findByPaymentKey(paymentKey);
        } catch (RuntimeException exception) {
            log.warn(
                    "웹훅 처리 중 PG 조회에 실패했습니다. orderId={} exceptionClass={}",
                    order.getOrderId(),
                    exception.getClass().getSimpleName()
            );
            return PgWebhookEventResult.PG_LOOKUP_ERROR;
        }

        if (lookup.status() != PgPaymentLookupResult.Status.APPROVED) {
            return PgWebhookEventResult.PG_NOT_APPROVED;
        }
        if (!matches(order, lookup)) {
            log.warn(
                    "웹훅 처리 중 PG 조회 응답이 로컬 주문과 일치하지 않습니다. orderId={}",
                    order.getOrderId()
            );
            return PgWebhookEventResult.PG_MISMATCH;
        }

        try {
            boolean transitioned = webhookConfirmTransactionService.completeIfNotPaid(
                    order.getOrderId(),
                    paymentKey,
                    clientIdempotencyKey
            );
            return transitioned
                    ? PgWebhookEventResult.CONFIRMED
                    : PgWebhookEventResult.ALREADY_PAID;
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.CHARGE_ORDER_STATE_CONFLICT) {
                return handleStateConflict(order, lookup);
            }
            logCompleteError(order, exception);
            return PgWebhookEventResult.COMPLETE_ERROR;
        } catch (RuntimeException exception) {
            logCompleteError(order, exception);
            return PgWebhookEventResult.COMPLETE_ERROR;
        }
    }

    private PgWebhookEventResult handleStateConflict(
            ChargeOrder order,
            PgPaymentLookupResult lookup
    ) {
        ChargeOrderStatus current = chargeOrderRepository.findByOrderId(order.getOrderId())
                .map(ChargeOrder::getStatus)
                .orElse(null);
        if (current == ChargeOrderStatus.PAID) {
            return PgWebhookEventResult.ALREADY_PAID;
        }
        recoveryTransactionService.recordApprovedStateConflict(order.getId(), lookup.rawStatus());
        return PgWebhookEventResult.STATE_CONFLICT;
    }

    /** 4단계 ChargeOrderRecoveryProcessor와 같은 대조 규칙(pg-charge.md 5단계 결정 5). */
    private boolean matches(ChargeOrder order, PgPaymentLookupResult lookup) {
        return order.getPaymentKey().equals(lookup.paymentKey())
                && order.getOrderId().equals(lookup.orderId())
                && lookup.totalAmount() != null && order.getAmount() == lookup.totalAmount()
                && "KRW".equals(lookup.currency())
                && lookup.rawStatus() != null && !lookup.rawStatus().isBlank();
    }

    private void logCompleteError(ChargeOrder order, RuntimeException exception) {
        log.error(
                "웹훅 처리 중 충전 확정(DB 2)에 실패했습니다. 복구 스케줄러가 다시 확인합니다. "
                        + "orderId={} exceptionClass={}",
                order.getOrderId(),
                exception.getClass().getSimpleName(),
                exception
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
