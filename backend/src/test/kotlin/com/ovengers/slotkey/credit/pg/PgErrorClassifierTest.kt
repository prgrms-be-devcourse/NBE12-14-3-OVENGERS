package com.ovengers.slotkey.credit.pg

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EmptySource
import org.junit.jupiter.params.provider.ValueSource

class PgErrorClassifierTest {
    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = ["   "])
    @DisplayName("4xx 응답의 에러 코드가 없으면 결과 모름으로 분류한다")
    fun classify_clientErrorWithoutCode_returnsIndeterminate(errorCode: String) {
        val result = PgErrorClassifier.classify(400, errorCode)

        assertThat(result.type).isEqualTo(PgConfirmationResult.Type.UNKNOWN)
        assertThat(result.unknownReason).isEqualTo(PgConfirmationResult.UnknownReason.INDETERMINATE)
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = ["   "])
    @DisplayName("5xx 응답의 에러 코드가 없으면 결과 모름으로 분류한다")
    fun classify_serverErrorWithoutCode_returnsIndeterminate(errorCode: String) {
        val result = PgErrorClassifier.classify(500, errorCode)

        assertThat(result.type).isEqualTo(PgConfirmationResult.Type.UNKNOWN)
        assertThat(result.unknownReason).isEqualTo(PgConfirmationResult.UnknownReason.INDETERMINATE)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "REJECT_CARD_PAYMENT",
            "INVALID_REJECT_CARD",
            "REJECT_CARD_COMPANY",
            "REJECT_ACCOUNT_PAYMENT",
            "INVALID_STOPPED_CARD",
            "INVALID_CARD_NUMBER",
            "INVALID_CARD_EXPIRATION",
            "INVALID_CARD_LOST_OR_STOLEN",
            "INVALID_PASSWORD",
            "EXCEED_MAX_AUTH_COUNT",
            "EXCEED_MAX_AMOUNT",
            "EXCEED_MAX_DAILY_PAYMENT_COUNT",
            "EXCEED_MAX_PAYMENT_AMOUNT",
            "BELOW_MINIMUM_AMOUNT",
            "NOT_FOUND_PAYMENT_SESSION",
            "PAY_PROCESS_ABORTED",
            "PAY_PROCESS_CANCELED",
        ],
    )
    @DisplayName("공식 문서의 사용자 측 거절 코드는 명확한 거절로 분류한다")
    fun classify_userDecline_returnsDeclined(errorCode: String) {
        val result = PgErrorClassifier.classify(400, errorCode)

        assertThat(result.type).isEqualTo(PgConfirmationResult.Type.DECLINED)
        assertThat(result.errorCode).isEqualTo(errorCode)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "INVALID_API_KEY",
            "INVALID_CLIENT_KEY",
            "UNAUTHORIZED_KEY",
            "API_KEY_ACCESS_DENIED",
            "FORBIDDEN_REQUEST",
            "INVALID_REQUEST",
            "INVALID_PAYMENT_KEY",
        ],
    )
    @DisplayName("공식 문서의 인증 키·요청 설정 오류는 우리 측 문제로 분류한다")
    fun classify_merchantProblem_returnsUnknownMerchantError(errorCode: String) {
        val result = PgErrorClassifier.classify(400, errorCode)

        assertThat(result.type).isEqualTo(PgConfirmationResult.Type.UNKNOWN)
        assertThat(result.unknownReason).isEqualTo(PgConfirmationResult.UnknownReason.MERCHANT_ERROR)
    }

    @ParameterizedTest
    @ValueSource(strings = ["COMMON_ERROR", "UNKNOWN_ERROR", "FAILED_INTERNAL_SYSTEM_PROCESSING"])
    @DisplayName("5xx 및 분류되지 않은 오류는 결과 모름으로 분류한다")
    fun classify_indeterminate_returnsUnknown(errorCode: String) {
        val result = PgErrorClassifier.classify(500, errorCode)

        assertThat(result.type).isEqualTo(PgConfirmationResult.Type.UNKNOWN)
        assertThat(result.unknownReason).isEqualTo(PgConfirmationResult.UnknownReason.INDETERMINATE)
    }

    @Test
    @DisplayName("ALREADY_PROCESSED_PAYMENT는 결제 조회가 필요한 결과로 분류한다")
    fun classify_alreadyProcessed_requiresLookup() {
        val result =
            PgErrorClassifier.classify(
                400,
                "ALREADY_PROCESSED_PAYMENT",
            )

        assertThat(result.type).isEqualTo(PgConfirmationResult.Type.UNKNOWN)
        assertThat(result.unknownReason).isEqualTo(PgConfirmationResult.UnknownReason.ALREADY_PROCESSED)
    }

    @Test
    @DisplayName("에러 코드 문구와 무관하게 5xx 응답은 결과 모름으로 분류한다")
    fun classify_serverError_isAlwaysUnknown() {
        val result =
            PgErrorClassifier.classify(
                500,
                "REJECT_CARD_PAYMENT",
            )

        assertThat(result.type).isEqualTo(PgConfirmationResult.Type.UNKNOWN)
        assertThat(result.unknownReason).isEqualTo(PgConfirmationResult.UnknownReason.INDETERMINATE)
    }
}
