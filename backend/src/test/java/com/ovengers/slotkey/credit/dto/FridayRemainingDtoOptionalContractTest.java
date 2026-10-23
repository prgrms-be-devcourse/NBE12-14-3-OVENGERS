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
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class FridayRemainingDtoOptionalContractTest {
    @Autowired private ObjectMapper objectMapper;

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("optionalComponents")
    void absenceComponentUsesOptional(Class<?> type, String componentName) {
        RecordComponent component = Arrays.stream(type.getRecordComponents())
                .filter(it -> it.getName().equals(componentName))
                .findFirst()
                .orElseThrow();
        assertThat(component.getType()).isEqualTo(Optional.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingRequiredRequests")
    void missingRequiredRequestUsesEmptyOptionalAndKeepsValidation(
            Class<?> type, String json, String componentName, String message) throws Exception {
        Object request = objectMapper.readValue(json, type);
        Object value = type.getMethod(componentName).invoke(request);
        assertThat(value).isInstanceOf(Optional.class);
        assertThat((Optional<?>) value).isEmpty();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);
            assertThat(violations).anySatisfy(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo(componentName);
                assertThat(violation.getMessage()).isEqualTo(message);
            });
        }
    }

    static Stream<Arguments> optionalComponents() {
        return Stream.of(
                Arguments.of(ChargeOrderCreateRequest.class, "amount"),
                Arguments.of(ChargeOrderConfirmRequest.class, "paymentKey"),
                Arguments.of(ChargeOrderConfirmRequest.class, "orderId"),
                Arguments.of(ChargeOrderConfirmRequest.class, "amount"),
                Arguments.of(ChargeOrderHistoryResponse.class, "paymentMethod"),
                Arguments.of(ChargeOrderHistoryResponse.class, "paymentProvider"),
                Arguments.of(PgConfirmationResult.class, "paymentKey"),
                Arguments.of(PgConfirmationResult.class, "errorCode"),
                Arguments.of(PgConfirmationResult.class, "unknownReason"),
                Arguments.of(PgConfirmationResult.class, "paymentMethod"),
                Arguments.of(PgConfirmationResult.class, "paymentProvider"),
                Arguments.of(PgPaymentLookupResult.class, "paymentKey"),
                Arguments.of(PgPaymentLookupResult.class, "orderId"),
                Arguments.of(PgPaymentLookupResult.class, "totalAmount"),
                Arguments.of(PgPaymentLookupResult.class, "currency"),
                Arguments.of(PgPaymentLookupResult.class, "rawStatus"),
                Arguments.of(PgWebhookEvent.class, "paymentKey"),
                Arguments.of(PgWebhookEvent.class, "reportedStatus"),
                Arguments.of(PgWebhookEvent.class, "retriedCount"),
                Arguments.of(WaitlistApplyRequest.class, "spaceId"),
                Arguments.of(WaitlistApplyRequest.class, "startTime"),
                Arguments.of(WaitlistApplyRequest.class, "endTime")
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
