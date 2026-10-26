package com.ovengers.slotkey.credit.webhook

import com.ovengers.slotkey.support.HttpBodyGoldenSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class TossWebhookEmptyBodyTest : HttpBodyGoldenSupport() {
    @Test
    fun receivedWebhookReturns200WithZeroBodyBytes() {
        // Existing receiver contract; no external PG call or request schema validation here.
        val body = """{"eventType":"PAYMENT_STATUS_CHANGED","data":{"orderId":"order-webhook-body"}}"""
        val request =
            post("/api/v1/webhooks/tosspayments")
                .header("tosspayments-webhook-transmission-id", "transmission-body")
                .header("tosspayments-webhook-transmission-retried-count", "0")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        val response =
            mvc
                .perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .response

        assertThat(response.contentAsByteArray).isEmpty()
        verify(receiver).receive("transmission-body", "0", body)
        verifyNoMoreInteractions(receiver)
    }
}
