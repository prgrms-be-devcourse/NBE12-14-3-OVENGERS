package com.ovengers.slotkey.credit.scheduler;

import com.ovengers.slotkey.credit.pg.PgClient;
import com.ovengers.slotkey.credit.pg.PgPaymentLookupResult;
import com.ovengers.slotkey.credit.service.ChargeOrderConfirmTransactionService;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class ChargeOrderRecoveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChargeOrderRecoveryProcessor.class);

    private final ChargeOrderRecoveryTransactionService recoveryTransactionService;
    private final ChargeOrderConfirmTransactionService confirmTransactionService;
    private final PgClient pgClient;

    public ChargeOrderRecoveryProcessor(
            ChargeOrderRecoveryTransactionService recoveryTransactionService,
            ChargeOrderConfirmTransactionService confirmTransactionService,
            PgClient pgClient
    ) {
        this.recoveryTransactionService = recoveryTransactionService;
        this.confirmTransactionService = confirmTransactionService;
        this.pgClient = pgClient;
    }

    public void recover(Long chargeOrderId, LocalDateTime now) {
        ChargeOrderRecoveryTarget target =
                recoveryTransactionService.prepare(chargeOrderId, now);
        if (target == null) {
            return;
        }
        PgPaymentLookupResult result = null;
        try {
            result = pgClient.findByPaymentKey(target.paymentKey());
            if (result.paymentObjectReceived() && !matches(target, result)) {
                recoveryTransactionService.markManualReview(
                        chargeOrderId,
                        now,
                        "PG_PAYMENT_MISMATCH"
                );
                return;
            }
            switch (result.status()) {
                case APPROVED -> confirmTransactionService.completeApproved(
                        target.orderId(),
                        target.paymentKey(),
                        target.clientIdempotencyKey()
                );
                case FAILED -> completeFailed(target, result, now);
                case UNKNOWN -> recoveryTransactionService.reschedule(
                        chargeOrderId,
                        now.plusMinutes(1)
                );
            }
        } catch (BusinessException exception) {
            if (isApprovedStateConflict(result, exception)) {
                recoveryTransactionService.recordApprovedStateConflict(
                        chargeOrderId,
                        result.rawStatus()
                );
                return;
            }
            recoveryTransactionService.reschedule(chargeOrderId, now.plusMinutes(1));
            throw exception;
        } catch (RuntimeException exception) {
            recoveryTransactionService.reschedule(chargeOrderId, now.plusMinutes(1));
            throw exception;
        }
    }

    public void markExpired(Long chargeOrderId, LocalDateTime now) {
        recoveryTransactionService.markManualReview(
                chargeOrderId,
                now,
                "RECOVERY_WINDOW_EXCEEDED"
        );
    }

    private void completeFailed(
            ChargeOrderRecoveryTarget target,
            PgPaymentLookupResult result,
            LocalDateTime now
    ) {
        if (result.rawStatus() == null || result.rawStatus().isBlank()) {
            recoveryTransactionService.markManualReview(
                    target.chargeOrderId(),
                    now,
                    "PG_STATUS_MISSING"
            );
            return;
        }
        recoveryTransactionService.markFailed(
                target.chargeOrderId(),
                "PG_STATUS_" + result.rawStatus(),
                now
        );
    }

    private boolean matches(
            ChargeOrderRecoveryTarget target,
            PgPaymentLookupResult result
    ) {
        boolean matches = target.paymentKey().equals(result.paymentKey())
                && target.orderId().equals(result.orderId())
                && result.totalAmount() != null && target.amount() == result.totalAmount()
                && "KRW".equals(result.currency())
                && result.rawStatus() != null && !result.rawStatus().isBlank();
        if (!matches) {
            log.warn(
                    "PG 조회 응답이 로컬 주문과 일치하지 않습니다. orderId={} lookupStatus={}",
                    target.orderId(),
                    result.status()
            );
        }
        return matches;
    }

    private boolean isApprovedStateConflict(
            PgPaymentLookupResult result,
            BusinessException exception
    ) {
        return result != null
                && result.status() == PgPaymentLookupResult.Status.APPROVED
                && exception.getErrorCode() == ErrorCode.CHARGE_ORDER_STATE_CONFLICT;
    }
}
