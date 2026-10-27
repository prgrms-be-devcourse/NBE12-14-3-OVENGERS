package com.ovengers.slotkey.credit.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.credit.dto.request.ChargeOrderConfirmRequest
import com.ovengers.slotkey.credit.dto.request.ChargeOrderCreateRequest
import com.ovengers.slotkey.credit.dto.response.ChargeOrderHistoryResponse
import com.ovengers.slotkey.credit.pg.PgConfirmationResult
import com.ovengers.slotkey.credit.pg.PgPaymentLookupResult
import com.ovengers.slotkey.credit.webhook.PgWebhookEvent
import com.ovengers.slotkey.waitlist.dto.request.WaitlistApplyRequest
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import java.time.LocalDateTime
import java.util.stream.Stream

@JsonTest
class FridayRemainingDtoNullableContractTest {
    @Autowired private lateinit var objectMapper: ObjectMapper

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("nullableComponents")
    fun absenceComponentUsesNullableJvmType(
        type: Class<*>,
        componentName: String,
        expectedType: Class<*>,
    ) {
        val component =
            type.recordComponents
                .asSequence()
                .first { it.name == componentName }

        assertThat(component.type).isEqualTo(expectedType)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingRequiredRequests")
    fun missingRequiredRequestUsesNullAndKeepsValidation(
        type: Class<*>,
        json: String,
        componentName: String,
        message: String,
    ) {
        val request = objectMapper.readValue(json, type)

        assertThat(type.getMethod(componentName).invoke(request)).isNull()

        Validation.buildDefaultValidatorFactory().use { factory ->
            val violations = factory.validator.validate(request)
            assertThat(violations).anySatisfy { violation ->
                assertThat(violation.propertyPath.toString()).isEqualTo(componentName)

                if (type == WaitlistApplyRequest::class.java) {
                    assertThat(violation.messageTemplate).isEqualTo(message)
                } else {
                    assertThat(violation.message).isEqualTo(message)
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun nullableComponents(): Stream<Arguments> =
            Stream.of(
                Arguments.of(ChargeOrderCreateRequest::class.java, "amount", Long::class.javaObjectType),
                Arguments.of(ChargeOrderConfirmRequest::class.java, "paymentKey", String::class.java),
                Arguments.of(ChargeOrderConfirmRequest::class.java, "orderId", String::class.java),
                Arguments.of(ChargeOrderConfirmRequest::class.java, "amount", Long::class.javaObjectType),
                Arguments.of(ChargeOrderHistoryResponse::class.java, "paymentMethod", String::class.java),
                Arguments.of(ChargeOrderHistoryResponse::class.java, "paymentProvider", String::class.java),
                Arguments.of(PgConfirmationResult::class.java, "paymentKey", String::class.java),
                Arguments.of(PgConfirmationResult::class.java, "errorCode", String::class.java),
                Arguments.of(PgConfirmationResult::class.java, "unknownReason", PgConfirmationResult.UnknownReason::class.java),
                Arguments.of(PgConfirmationResult::class.java, "paymentMethod", String::class.java),
                Arguments.of(PgConfirmationResult::class.java, "paymentProvider", String::class.java),
                Arguments.of(PgPaymentLookupResult::class.java, "paymentKey", String::class.java),
                Arguments.of(PgPaymentLookupResult::class.java, "orderId", String::class.java),
                Arguments.of(PgPaymentLookupResult::class.java, "totalAmount", Long::class.javaObjectType),
                Arguments.of(PgPaymentLookupResult::class.java, "currency", String::class.java),
                Arguments.of(PgPaymentLookupResult::class.java, "rawStatus", String::class.java),
                Arguments.of(PgWebhookEvent::class.java, "paymentKey", String::class.java),
                Arguments.of(PgWebhookEvent::class.java, "reportedStatus", String::class.java),
                Arguments.of(PgWebhookEvent::class.java, "retriedCount", Int::class.javaObjectType),
                Arguments.of(WaitlistApplyRequest::class.java, "spaceId", Long::class.javaObjectType),
                Arguments.of(WaitlistApplyRequest::class.java, "startTime", LocalDateTime::class.java),
                Arguments.of(WaitlistApplyRequest::class.java, "endTime", LocalDateTime::class.java),
            )

        @JvmStatic
        fun missingRequiredRequests(): Stream<Arguments> =
            Stream.of(
                Arguments.of(ChargeOrderCreateRequest::class.java, "{}", "amount", "충전 금액은 필수입니다."),
                Arguments.of(
                    ChargeOrderConfirmRequest::class.java,
                    "{\"orderId\":\"order-1\",\"amount\":1000}",
                    "paymentKey",
                    "paymentKey는 필수입니다.",
                ),
                Arguments.of(
                    WaitlistApplyRequest::class.java,
                    "{\"startTime\":\"2026-10-25T10:00:00\",\"endTime\":\"2026-10-25T11:00:00\"}",
                    "spaceId",
                    "{jakarta.validation.constraints.NotNull.message}",
                ),
            )
    }
}
