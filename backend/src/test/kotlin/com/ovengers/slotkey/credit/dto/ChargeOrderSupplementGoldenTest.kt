package com.ovengers.slotkey.credit.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.credit.dto.response.ChargeOrderHistoryResponse
import com.ovengers.slotkey.credit.dto.response.ChargeOrderResponse
import com.ovengers.slotkey.credit.entity.ChargeOrder
import com.ovengers.slotkey.support.SurveyGoldenAssertions.assertGolden
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import java.time.LocalDateTime

@JsonTest
class ChargeOrderSupplementGoldenTest {
    @Autowired
    private lateinit var mapper: ObjectMapper

    @Test
    fun paidHistoryIncludesPaymentDisplayAndUpdatedTime() {
        val order = ChargeOrder.createPending(1L, "order-history-paid", 1000L, created)
        order.recordPaymentMethod("CARD", "TOSS_PAYMENTS")
        order.markPaid(created.plusMinutes(1))
        assertGolden(mapper, "credit/history-paid-response", ChargeOrderHistoryResponse.from(order))
    }

    @Test
    fun failedOrderKeepsFailedStatus() {
        val order = ChargeOrder.createPending(1L, "order-failed", 1000L, created)
        order.markFailed("PG_REJECTED", created.plusMinutes(1))
        assertGolden(mapper, "credit/order-failed-response", ChargeOrderResponse.from(order))
    }

    companion object {
        private val created = LocalDateTime.of(2026, 10, 23, 9, 0)
    }
}
