package com.ovengers.slotkey.credit.controller

import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.support.HttpBodyGoldenSupport
import com.ovengers.slotkey.support.SurveyGoldenAssertions.readGolden
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ChargeConfirmHttpBodyGoldenTest : HttpBodyGoldenSupport() {
    @Test
    fun confirmingReturns202WithJavaBody() {
        val orderId = "chg_550e8400-e29b-41d4-a716-446655440000"
        given(confirmations.confirm(7L, "payment-key", orderId, 1000L, "request-key"))
            .willReturn(ChargeOrderConfirmResponse(orderId, 1000L, ChargeOrderStatus.CONFIRMING))

        val request =
            post("/api/v1/credits/charge-orders/confirm")
                .header("Authorization", authorization())
                .header("Idempotency-Key", "request-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"paymentKey":"payment-key","orderId":"$orderId","amount":1000}""")
        val response =
            mvc
                .perform(request)
                .andExpect(status().isAccepted())
                .andReturn()
                .response

        assertThat(mapper.readTree(response.contentAsByteArray))
            .isEqualTo(readGolden(mapper, "credit/confirm-confirming-accepted"))
        verify(confirmations).confirm(7L, "payment-key", orderId, 1000L, "request-key")
    }
}
