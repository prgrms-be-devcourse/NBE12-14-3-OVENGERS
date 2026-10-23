package com.ovengers.slotkey.credit.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.credit.dto.request.ChargeOrderConfirmRequest;
import com.ovengers.slotkey.credit.dto.request.ChargeOrderCreateRequest;
import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse;
import com.ovengers.slotkey.credit.dto.response.ChargeOrderHistoryResponse;
import com.ovengers.slotkey.credit.dto.response.ChargeOrderResponse;
import com.ovengers.slotkey.credit.pg.PgConfirmRequest;
import com.ovengers.slotkey.credit.pg.PgConfirmationResult;
import com.ovengers.slotkey.credit.pg.PgPaymentLookupResult;
import com.ovengers.slotkey.credit.webhook.PgWebhookEvent;
import com.ovengers.slotkey.credit.webhook.PgWebhookEventResult;
import com.ovengers.slotkey.notification.dto.response.NotificationResponse;
import com.ovengers.slotkey.notification.dto.response.ReadAllResponse;
import com.ovengers.slotkey.notification.dto.response.UnreadCountResponse;
import com.ovengers.slotkey.waitlist.dto.request.WaitlistApplyRequest;
import com.ovengers.slotkey.waitlist.dto.response.WaitlistEntryResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Java 원본에서 고정한 나머지 금요일 DTO의 JSON 필드·값·null 계약. */
@JsonTest
class FridayRemainingDtoGoldenTest {
    @Autowired private ObjectMapper objectMapper;

    @ParameterizedTest(name = "{0}")
    @MethodSource("contracts")
    void roundTripKeepsJavaJson(String name, Class<?> type, String json) throws Exception {
        JsonNode expected = objectMapper.readTree(json);
        Object value = objectMapper.treeToValue(expected, type);
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(value))).isEqualTo(expected);
    }

    static Stream<Arguments> contracts() {
        return Stream.of(
                Arguments.of("charge-create", ChargeOrderCreateRequest.class, "{\"amount\":1000}"),
                Arguments.of("charge-confirm", ChargeOrderConfirmRequest.class,
                        "{\"paymentKey\":\"pay-key\",\"orderId\":\"order-1\",\"amount\":1000}"),
                Arguments.of("charge-order", ChargeOrderResponse.class,
                        "{\"orderId\":\"order-1\",\"amount\":1000,\"status\":\"PENDING\"}"),
                Arguments.of("charge-confirm-response", ChargeOrderConfirmResponse.class,
                        "{\"orderId\":\"order-1\",\"amount\":1000,\"status\":\"PAID\"}"),
                Arguments.of("charge-history-null-display", ChargeOrderHistoryResponse.class,
                        "{\"orderId\":\"order-1\",\"amount\":1000,\"status\":\"PAID\","
                                + "\"paymentMethod\":null,\"paymentProvider\":null,"
                                + "\"createdAt\":\"2026-10-23T09:00:00\",\"updatedAt\":\"2026-10-23T09:01:00\"}"),
                Arguments.of("pg-confirm-request", PgConfirmRequest.class,
                        "{\"paymentKey\":\"pay-key\",\"orderId\":\"order-1\",\"amount\":1000,"
                                + "\"idempotencyKey\":\"idem-1\"}"),
                Arguments.of("pg-confirmation-unknown", PgConfirmationResult.class,
                        "{\"type\":\"UNKNOWN\",\"paymentKey\":null,\"errorCode\":\"PG_TIMEOUT\","
                                + "\"unknownReason\":\"INDETERMINATE\",\"paymentMethod\":null,"
                                + "\"paymentProvider\":null}"),
                Arguments.of("pg-lookup-not-received", PgPaymentLookupResult.class,
                        "{\"status\":\"UNKNOWN\",\"paymentKey\":null,\"orderId\":null,"
                                + "\"totalAmount\":null,\"currency\":null,\"rawStatus\":null,"
                                + "\"paymentObjectReceived\":false}"),
                Arguments.of("webhook-null-hints", PgWebhookEvent.class,
                        "{\"transmissionId\":\"tx-1\",\"eventType\":\"PAYMENT_STATUS_CHANGED\","
                                + "\"orderId\":\"order-1\",\"paymentKey\":null,"
                                + "\"reportedStatus\":null,\"retriedCount\":null}"),
                Arguments.of("webhook-result", PgWebhookEventResult.class, "\"CONFIRMED\""),
                Arguments.of("waitlist-apply", WaitlistApplyRequest.class,
                        "{\"spaceId\":20,\"startTime\":\"2026-10-25T10:00:00\","
                                + "\"endTime\":\"2026-10-25T11:00:00\"}"),
                Arguments.of("waitlist-entry", WaitlistEntryResponse.class,
                        "{\"id\":3,\"spaceId\":20,\"startTime\":\"2026-10-25T10:00:00\","
                                + "\"endTime\":\"2026-10-25T11:00:00\",\"status\":\"WAITING\","
                                + "\"position\":2,\"createdAt\":\"2026-10-23T09:00:00\"}"),
                Arguments.of("notification", NotificationResponse.class,
                        "{\"id\":5,\"waitlistEntryId\":3,\"message\":\"빈자리가 생겼습니다.\","
                                + "\"read\":false,\"createdAt\":\"2026-10-23T09:00:00\"}"),
                Arguments.of("read-all", ReadAllResponse.class, "{\"updatedCount\":2}"),
                Arguments.of("unread-count", UnreadCountResponse.class, "{\"unreadCount\":4}")
        );
    }
}
