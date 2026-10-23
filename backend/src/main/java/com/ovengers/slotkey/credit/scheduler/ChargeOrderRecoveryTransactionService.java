package com.ovengers.slotkey.credit.scheduler;

import com.ovengers.slotkey.audit.entity.AuditAction;
import com.ovengers.slotkey.audit.entity.AuditTargetType;
import com.ovengers.slotkey.audit.service.AuditLogService;
import com.ovengers.slotkey.credit.entity.ChargeOrder;
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus;
import com.ovengers.slotkey.credit.repository.ChargeOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class ChargeOrderRecoveryTransactionService {

    private static final Logger log =
            LoggerFactory.getLogger(ChargeOrderRecoveryTransactionService.class);

    private final ChargeOrderRepository chargeOrderRepository;
    private final AuditLogService auditLogService;

    public ChargeOrderRecoveryTransactionService(
            ChargeOrderRepository chargeOrderRepository,
            AuditLogService auditLogService
    ) {
        this.chargeOrderRepository = chargeOrderRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public ChargeOrderRecoveryTarget prepare(Long chargeOrderId, LocalDateTime now) {
        ChargeOrder order = chargeOrderRepository.findByIdForUpdate(chargeOrderId)
                .orElse(null);
        if (order == null
                || order.getStatus() != ChargeOrderStatus.CONFIRMING
                || order.getRecoveryExhaustedAt() != null) {
            return null;
        }
        if (!order.getUpdatedAt().isAfter(now.minusHours(24))) {
            markManualReview(order, now, "RECOVERY_WINDOW_EXCEEDED");
            return null;
        }
        if (order.getPaymentKey() == null
                || order.getPaymentKey().isBlank()
                || order.getClientIdempotencyKey() == null
                || order.getClientIdempotencyKey().isBlank()
                || order.getRecoveryNextAttemptAt() == null) {
            markManualReview(order, now, "RECOVERY_INVARIANT_VIOLATION");
            return null;
        }
        if (order.getRecoveryNextAttemptAt().isAfter(now)) {
            return null;
        }
        return new ChargeOrderRecoveryTarget(
                order.getId(),
                order.getOrderId(),
                order.getPaymentKey(),
                order.getClientIdempotencyKey(),
                order.getAmount()
        );
    }

    @Transactional
    public void reschedule(Long chargeOrderId, LocalDateTime nextAttemptAt) {
        ChargeOrder order = chargeOrderRepository.findByIdForUpdate(chargeOrderId)
                .orElse(null);
        if (order != null
                && order.getStatus() == ChargeOrderStatus.CONFIRMING
                && order.getRecoveryExhaustedAt() == null) {
            order.scheduleNextRecovery(nextAttemptAt);
        }
    }

    @Transactional
    public void markFailed(Long chargeOrderId, String failureCode, LocalDateTime now) {
        ChargeOrder order = chargeOrderRepository.findByIdForUpdate(chargeOrderId)
                .orElse(null);
        if (order != null && order.getStatus() == ChargeOrderStatus.CONFIRMING) {
            order.markFailed(failureCode, now);
        }
    }

    @Transactional
    public void markManualReview(Long chargeOrderId, LocalDateTime now, String reason) {
        ChargeOrder order = chargeOrderRepository.findByIdForUpdate(chargeOrderId)
                .orElse(null);
        if (order != null
                && order.getStatus() == ChargeOrderStatus.CONFIRMING
                && order.getRecoveryExhaustedAt() == null) {
            markManualReview(order, now, reason);
        }
    }

    @Transactional
    public void recordApprovedStateConflict(Long chargeOrderId, String pgStatus) {
        ChargeOrder order = chargeOrderRepository.findByIdForUpdate(chargeOrderId)
                .orElse(null);
        if (order == null) {
            return;
        }
        String localStatus = order.getStatus().name();
        String reason = "PG_STATUS=" + pgStatus + ", LOCAL_STATUS=" + localStatus;
        auditLogService.log(
                null,
                AuditAction.CHARGE_RECOVERY_MANUAL_REVIEW,
                AuditTargetType.CHARGE_ORDER,
                order.getId(),
                reason,
                Map.of("localStatus", localStatus),
                Map.of("pgStatus", pgStatus, "localStatus", localStatus)
        );
        log.warn(
                "PG 승인과 로컬 주문 상태가 충돌했습니다. orderId={} "
                        + "reason=PG_APPROVED_LOCAL_FAILED_CONFLICT "
                        + "pgStatus={} localStatus={}",
                order.getOrderId(),
                pgStatus,
                localStatus
        );
    }

    private void markManualReview(
            ChargeOrder order,
            LocalDateTime now,
            String reason
    ) {
        Map<String, Object> beforeValue = Map.of(
                "status", order.getStatus().name(),
                "recoveryExhausted", false
        );
        order.markRecoveryExhausted(now);
        auditLogService.log(
                null,
                AuditAction.CHARGE_RECOVERY_MANUAL_REVIEW,
                AuditTargetType.CHARGE_ORDER,
                order.getId(),
                reason,
                beforeValue,
                Map.of(
                        "status", order.getStatus().name(),
                        "recoveryExhausted", true,
                        "recoveryReason", reason
                )
        );
        log.warn(
                "충전 주문을 수동 처리 대상으로 표시했습니다. orderId={} reason={}",
                order.getOrderId(),
                reason
        );
    }
}
