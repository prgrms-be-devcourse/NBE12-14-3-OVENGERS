package com.ovengers.slotkey.credit.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.credit.dto.request.ChargeOrderConfirmRequest
import com.ovengers.slotkey.credit.dto.request.ChargeOrderCreateRequest
import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse
import com.ovengers.slotkey.credit.dto.response.ChargeOrderHistoryResponse
import com.ovengers.slotkey.credit.dto.response.ChargeOrderResponse
import com.ovengers.slotkey.credit.pg.PgConfirmRequest
import com.ovengers.slotkey.credit.pg.PgConfirmationResult
import com.ovengers.slotkey.credit.pg.PgPaymentLookupResult
import com.ovengers.slotkey.credit.webhook.PgWebhookEvent
import com.ovengers.slotkey.credit.webhook.PgWebhookEventResult
import com.ovengers.slotkey.notification.dto.response.NotificationResponse
import com.ovengers.slotkey.notification.dto.response.ReadAllResponse
import com.ovengers.slotkey.notification.dto.response.UnreadCountResponse
import com.ovengers.slotkey.waitlist.dto.request.WaitlistApplyRequest
import com.ovengers.slotkey.waitlist.dto.response.WaitlistEntryResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import java.util.stream.Stream

/** Java 원본에서 고정한 나머지 금요일 DTO의 JSON 필드·값·null 계약. */
@JsonTest
class FridayRemainingDtoGoldenTest {
    @Autowired private lateinit var objectMapper: ObjectMapper

    @ParameterizedTest(name = "{0}")
    @MethodSource("contracts")
    fun roundTripKeepsJavaJson(
        @Suppress("UNUSED_PARAMETER") name: String,
        type: Class<*>,
        json: String,
    ) {
        val expected = objectMapper.readTree(json)
        val value = objectMapper.treeToValue(expected, type)
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(value))).isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        fun contracts(): Stream<Arguments> =
            Stream.of(
                Arguments.of("charge-create", ChargeOrderCreateRequest::class.java, "{\"amount\":1000}"),
                Arguments.of(
                    "charge-confirm",
                    ChargeOrderConfirmRequest::class.java,
                    "{\"paymentKey\":\"pay-key\",\"orderId\":\"order-1\",\"amount\":1000}",
                ),
                Arguments.of(
                    "charge-order",
                    ChargeOrderResponse::class.java,
                    "{\"orderId\":\"order-1\",\"amount\":1000,\"status\":\"PENDING\"}",
                ),
                Arguments.of(
                    "charge-confirm-response",
                    ChargeOrderConfirmResponse::class.java,
                    "{\"orderId\":\"order-1\",\"amount\":1000,\"status\":\"PAID\"}",
                ),
                Arguments.of(
                    "charge-history-null-display",
                    ChargeOrderHistoryResponse::class.java,
                    "{\"orderId\":\"order-1\",\"amount\":1000,\"status\":\"PAID\"," +
                        "\"paymentMethod\":null,\"paymentProvider\":null," +
                        "\"createdAt\":\"2026-10-23T09:00:00\",\"updatedAt\":\"2026-10-23T09:01:00\"}",
                ),
                Arguments.of(
                    "pg-confirm-request",
                    PgConfirmRequest::class.java,
                    "{\"paymentKey\":\"pay-key\",\"orderId\":\"order-1\",\"amount\":1000," +
                        "\"idempotencyKey\":\"idem-1\"}",
                ),
                Arguments.of(
                    "pg-confirmation-unknown",
                    PgConfirmationResult::class.java,
                    "{\"type\":\"UNKNOWN\",\"paymentKey\":null,\"errorCode\":\"PG_TIMEOUT\"," +
                        "\"unknownReason\":\"INDETERMINATE\",\"paymentMethod\":null," +
                        "\"paymentProvider\":null}",
                ),
                Arguments.of(
                    "pg-lookup-not-received",
                    PgPaymentLookupResult::class.java,
                    "{\"status\":\"UNKNOWN\",\"paymentKey\":null,\"orderId\":null," +
                        "\"totalAmount\":null,\"currency\":null,\"rawStatus\":null," +
                        "\"paymentObjectReceived\":false}",
                ),
                Arguments.of(
                    "webhook-null-hints",
                    PgWebhookEvent::class.java,
                    "{\"transmissionId\":\"tx-1\",\"eventType\":\"PAYMENT_STATUS_CHANGED\"," +
                        "\"orderId\":\"order-1\",\"paymentKey\":null," +
                        "\"reportedStatus\":null,\"retriedCount\":null}",
                ),
                Arguments.of("webhook-result", PgWebhookEventResult::class.java, "\"CONFIRMED\""),
                Arguments.of(
                    "waitlist-apply",
                    WaitlistApplyRequest::class.java,
                    "{\"spaceId\":20,\"startTime\":\"2026-10-25T10:00:00\"," +
                        "\"endTime\":\"2026-10-25T11:00:00\"}",
                ),
                Arguments.of(
                    "waitlist-entry",
                    WaitlistEntryResponse::class.java,
                    "{\"id\":3,\"spaceId\":20,\"startTime\":\"2026-10-25T10:00:00\"," +
                        "\"endTime\":\"2026-10-25T11:00:00\",\"status\":\"WAITING\"," +
                        "\"position\":2,\"createdAt\":\"2026-10-23T09:00:00\"}",
                ),
                Arguments.of(
                    "notification",
                    NotificationResponse::class.java,
                    "{\"id\":5,\"waitlistEntryId\":3,\"message\":\"빈자리가 생겼습니다.\"," +
                        "\"read\":false,\"createdAt\":\"2026-10-23T09:00:00\"}",
                ),
                Arguments.of("read-all", ReadAllResponse::class.java, "{\"updatedCount\":2}"),
                Arguments.of("unread-count", UnreadCountResponse::class.java, "{\"unreadCount\":4}"),
            )
    }
}
