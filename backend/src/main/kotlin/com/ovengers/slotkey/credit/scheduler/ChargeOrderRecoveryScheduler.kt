package com.ovengers.slotkey.credit.scheduler

import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.repository.ChargeOrderRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

@Component
@ConditionalOnProperty(
    prefix = "slotkey.scheduler.charge-recovery",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ChargeOrderRecoveryScheduler(
    private val chargeOrderRepository: ChargeOrderRepository,
    private val processor: ChargeOrderRecoveryProcessor,
    private val clock: Clock,
    @Value("\${slotkey.scheduler.charge-recovery.batch-size:50}") private val batchSize: Int,
) {
    @Scheduled(
        fixedDelayString = "\${slotkey.scheduler.charge-recovery.fixed-delay-ms:60000}",
        initialDelayString = "\${slotkey.scheduler.charge-recovery.initial-delay-ms:60000}",
    )
    fun recoverChargeOrders() {
        val now = LocalDateTime.now(clock)
        val recoveryCutoff = now.minusHours(24)
        val firstBatch = PageRequest.of(0, batchSize)

        val exhaustedIds =
            chargeOrderRepository.findRecoveryExhaustedCandidateIds(
                ChargeOrderStatus.CONFIRMING,
                recoveryCutoff,
                firstBatch,
            )
        processEach("CHARGE_RECOVERY_EXHAUSTED", exhaustedIds) { processor.markExpired(it, now) }

        val recoveryIds =
            chargeOrderRepository.findRecoveryCandidateIds(
                ChargeOrderStatus.CONFIRMING,
                now,
                recoveryCutoff,
                firstBatch,
            )
        processEach("CHARGE_RECOVERY", recoveryIds) { processor.recover(it, now) }
    }

    private fun processEach(
        jobName: String,
        chargeOrderIds: List<Long>,
        action: (Long) -> Unit,
    ) {
        if (chargeOrderIds.isEmpty()) return

        var completed = 0
        chargeOrderIds.forEach { chargeOrderId ->
            try {
                action(chargeOrderId)
                completed++
            } catch (exception: RuntimeException) {
                log.error(
                    "[{}] chargeOrderId={} 처리 실패 exceptionClass={}",
                    jobName,
                    chargeOrderId,
                    exception.javaClass.simpleName,
                    exception,
                )
            }
        }
        log.info("[{}] 대상 {}건 중 {}건 처리", jobName, chargeOrderIds.size, completed)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChargeOrderRecoveryScheduler::class.java)
    }
}
