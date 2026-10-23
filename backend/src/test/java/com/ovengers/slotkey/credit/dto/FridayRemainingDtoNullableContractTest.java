package com.ovengers.slotkey.credit.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.credit.dto.request.ChargeOrderConfirmRequest;
import com.ovengers.slotkey.credit.dto.request.ChargeOrderCreateRequest;
import com.ovengers.slotkey.credit.dto.response.ChargeOrderHistoryResponse;
import com.ovengers.slotkey.credit.pg.PgConfirmationResult;
import com.ovengers.slotkey.credit.pg.PgPaymentLookupResult;
import com.ovengers.slotkey.credit.webhook.PgWebhookEvent;
import com.ovengers.slotkey.waitlist.dto.request.WaitlistApplyRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class FridayRemainingDtoNullableContractTest {
    @Autowired private ObjectMapper objectMapper;

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("nullableComponents")
    void absenceComponentUsesNullableJvmType(Class<?> type, String componentName, Class<?> expectedType) {
        RecordComponent component = Arrays.stream(type.getRecordComponents())
                .filter(it -> it.getName().equals(componentName))
                .findFirst()
                .orElseThrow();

        assertThat(component.getType()).isEqualTo(expectedType);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingRequiredRequests")
    void missingRequiredRequestUsesNullAndKeepsValidation(
            Class<?> type, String json, String componentName, String message) throws Exception {
        Object request = objectMapper.readValue(json, type);

        assertThat(type.getMethod(componentName).invoke(request)).isNull();

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);
            assertThat(violations).anySatisfy(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo(componentName);
                assertThat(violation.getMessage()).isEqualTo(message);
            });
        }
    }

    static Stream<Arguments> nullableComponents() {
        return Stream.of(
                Arguments.of(ChargeOrderCreateRequest.class, "amount", Long.class),
                Arguments.of(ChargeOrderConfirmRequest.class, "paymentKey", String.class),
                Arguments.of(ChargeOrderConfirmRequest.class, "orderId", String.class),
                Arguments.of(ChargeOrderConfirmRequest.class, "amount", Long.class),
                Arguments.of(ChargeOrderHistoryResponse.class, "paymentMethod", String.class),
                Arguments.of(ChargeOrderHistoryResponse.class, "paymentProvider", String.class),
                Arguments.of(PgConfirmationResult.class, "paymentKey", String.class),
                Arguments.of(PgConfirmationResult.class, "errorCode", String.class),
                Arguments.of(PgConfirmationResult.class, "unknownReason", PgConfirmationResult.UnknownReason.class),
                Arguments.of(PgConfirmationResult.class, "paymentMethod", String.class),
                Arguments.of(PgConfirmationResult.class, "paymentProvider", String.class),
                Arguments.of(PgPaymentLookupResult.class, "paymentKey", String.class),
                Arguments.of(PgPaymentLookupResult.class, "orderId", String.class),
                Arguments.of(PgPaymentLookupResult.class, "totalAmount", Long.class),
                Arguments.of(PgPaymentLookupResult.class, "currency", String.class),
                Arguments.of(PgPaymentLookupResult.class, "rawStatus", String.class),
                Arguments.of(PgWebhookEvent.class, "paymentKey", String.class),
                Arguments.of(PgWebhookEvent.class, "reportedStatus", String.class),
                Arguments.of(PgWebhookEvent.class, "retriedCount", Integer.class),
                Arguments.of(WaitlistApplyRequest.class, "spaceId", Long.class),
                Arguments.of(WaitlistApplyRequest.class, "startTime", LocalDateTime.class),
                Arguments.of(WaitlistApplyRequest.class, "endTime", LocalDateTime.class)
        );
    }

    static Stream<Arguments> missingRequiredRequests() {
        return Stream.of(
                Arguments.of(ChargeOrderCreateRequest.class, "{}", "amount", "충전 금액은 필수입니다."),
                Arguments.of(ChargeOrderConfirmRequest.class,
                        "{\"orderId\":\"order-1\",\"amount\":1000}",
                        "paymentKey", "paymentKey는 필수입니다."),
                Arguments.of(WaitlistApplyRequest.class,
                        "{\"startTime\":\"2026-10-25T10:00:00\",\"endTime\":\"2026-10-25T11:00:00\"}",
                        "spaceId", "널이어서는 안됩니다")
        );
    }
}
