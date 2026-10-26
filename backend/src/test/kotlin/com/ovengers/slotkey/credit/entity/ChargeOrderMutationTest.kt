package com.ovengers.slotkey.credit.entity

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ChargeOrderMutationTest {
    @Test
    fun `pending order rejects a null member id`() {
        assertThatThrownBy {
            ChargeOrder.createPending(
                null,
                "chg_mutation_test",
                1_000L,
                LocalDateTime.of(2026, 10, 26, 12, 0),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
