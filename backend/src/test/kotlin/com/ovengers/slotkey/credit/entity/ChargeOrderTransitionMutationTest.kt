package com.ovengers.slotkey.credit.entity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ChargeOrderTransitionMutationTest {
    @Test
    fun `markPaid clears recovery state and records the transition time`() {
        val createdAt = LocalDateTime.of(2026, 10, 26, 9, 0)
        val recoveryAt = LocalDateTime.of(2026, 10, 26, 9, 10)
        val paidAt = LocalDateTime.of(2026, 10, 26, 9, 5)
        val order = ChargeOrder.createPending(1L, "chg_mark_paid_mutation", 1_000L, createdAt)
        order.markFailed("PREVIOUS_FAILURE", createdAt.plusMinutes(1))
        order.scheduleNextRecovery(recoveryAt)

        order.markPaid(paidAt)

        assertThat(order.status).isEqualTo(ChargeOrderStatus.PAID)
        assertThat(order.failureCode).isNull()
        assertThat(order.recoveryNextAttemptAt).isNull()
        assertThat(order.updatedAt).isEqualTo(paidAt)
    }

    @Test
    fun `markFailed clears the recovery schedule and records the transition time`() {
        val createdAt = LocalDateTime.of(2026, 10, 26, 10, 0)
        val failedAt = LocalDateTime.of(2026, 10, 26, 10, 5)
        val order = ChargeOrder.createPending(1L, "chg_mark_failed_mutation", 1_000L, createdAt)
        order.scheduleNextRecovery(createdAt.plusMinutes(1))

        order.markFailed("PG_STATUS_EXPIRED", failedAt)

        assertThat(order.status).isEqualTo(ChargeOrderStatus.FAILED)
        assertThat(order.failureCode).isEqualTo("PG_STATUS_EXPIRED")
        assertThat(order.recoveryNextAttemptAt).isNull()
        assertThat(order.updatedAt).isEqualTo(failedAt)
    }
}
