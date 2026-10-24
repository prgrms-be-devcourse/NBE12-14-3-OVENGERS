package com.ovengers.slotkey.credit.scheduler

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.service.AuditLogService
import com.ovengers.slotkey.credit.entity.ChargeOrder
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.repository.ChargeOrderRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ChargeOrderRecoveryTransactionService(
    private val chargeOrderRepository: ChargeOrderRepository,
    private val auditLogService: AuditLogService,
) {
    @Transactional
    fun prepare(
        chargeOrderId: Long?,
        now: LocalDateTime,
    ): ChargeOrderRecoveryTarget? {
        val order = chargeOrderRepository.findByIdForUpdate(chargeOrderId) ?: return null
        if (order.status != ChargeOrderStatus.CONFIRMING || order.recoveryExhaustedAt != null) {
            return null
        }
        if (!order.updatedAt.isAfter(now.minusHours(24))) {
            markManualReview(order, now, "RECOVERY_WINDOW_EXCEEDED")
            return null
        }
        val paymentKey = order.paymentKey
        val clientIdempotencyKey = order.clientIdempotencyKey
        val recoveryNextAttemptAt = order.recoveryNextAttemptAt
        if (paymentKey.isNullOrBlank() || clientIdempotencyKey.isNullOrBlank() || recoveryNextAttemptAt == null) {
            markManualReview(order, now, "RECOVERY_INVARIANT_VIOLATION")
            return null
        }
        if (recoveryNextAttemptAt.isAfter(now)) {
            return null
        }
        return ChargeOrderRecoveryTarget(
            order.id,
            order.orderId,
            paymentKey,
            clientIdempotencyKey,
            order.amount,
        )
    }

    @Transactional
    fun reschedule(
        chargeOrderId: Long?,
        nextAttemptAt: LocalDateTime,
    ) {
        val order = chargeOrderRepository.findByIdForUpdate(chargeOrderId)
        if (order?.status == ChargeOrderStatus.CONFIRMING && order.recoveryExhaustedAt == null) {
            order.scheduleNextRecovery(nextAttemptAt)
        }
    }

    @Transactional
    fun markFailed(
        chargeOrderId: Long?,
        failureCode: String,
        now: LocalDateTime,
    ) {
        val order = chargeOrderRepository.findByIdForUpdate(chargeOrderId)
        if (order?.status == ChargeOrderStatus.CONFIRMING) {
            order.markFailed(failureCode, now)
        }
    }

    @Transactional
    fun markManualReview(
        chargeOrderId: Long?,
        now: LocalDateTime,
        reason: String,
    ) {
        val order = chargeOrderRepository.findByIdForUpdate(chargeOrderId)
        if (
            order?.status == ChargeOrderStatus.CONFIRMING &&
            order.recoveryExhaustedAt == null
        ) {
            markManualReview(order, now, reason)
        }
    }

    @Transactional
    fun recordApprovedStateConflict(
        chargeOrderId: Long?,
        pgStatus: String,
    ) {
        val order = chargeOrderRepository.findByIdForUpdate(chargeOrderId) ?: return
        val localStatus = order.status.name
        val reason = "PG_STATUS=$pgStatus, LOCAL_STATUS=$localStatus"
        auditLogService.log(
            null,
            AuditAction.CHARGE_RECOVERY_MANUAL_REVIEW,
            AuditTargetType.CHARGE_ORDER,
            order.id,
            reason,
            mapOf("localStatus" to localStatus),
            mapOf("pgStatus" to pgStatus, "localStatus" to localStatus),
        )
        log.warn(
            "PG 승인과 로컬 주문 상태가 충돌했습니다. orderId={} " +
                "reason=PG_APPROVED_LOCAL_FAILED_CONFLICT pgStatus={} localStatus={}",
            order.orderId,
            pgStatus,
            localStatus,
        )
    }

    private fun markManualReview(
        order: ChargeOrder,
        now: LocalDateTime,
        reason: String,
    ) {
        val beforeValue =
            mapOf(
                "status" to order.status.name,
                "recoveryExhausted" to false,
            )
        order.markRecoveryExhausted(now)
        auditLogService.log(
            null,
            AuditAction.CHARGE_RECOVERY_MANUAL_REVIEW,
            AuditTargetType.CHARGE_ORDER,
            order.id,
            reason,
            beforeValue,
            mapOf(
                "status" to order.status.name,
                "recoveryExhausted" to true,
                "recoveryReason" to reason,
            ),
        )
        log.warn(
            "충전 주문을 수동 처리 대상으로 표시했습니다. orderId={} reason={}",
            order.orderId,
            reason,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChargeOrderRecoveryTransactionService::class.java)
    }
}
