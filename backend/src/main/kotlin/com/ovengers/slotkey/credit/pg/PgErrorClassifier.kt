package com.ovengers.slotkey.credit.pg

object PgErrorClassifier {
    private val USER_DECLINE_CODES =
        setOf(
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
        )

    private val MERCHANT_ERROR_CODES =
        setOf(
            "INVALID_API_KEY",
            "INVALID_CLIENT_KEY",
            "UNAUTHORIZED_KEY",
            "API_KEY_ACCESS_DENIED",
            "FORBIDDEN_REQUEST",
            "INVALID_REQUEST",
            "INVALID_PAYMENT_KEY",
        )

    @JvmStatic
    fun classify(
        httpStatus: Int,
        errorCode: String,
    ): PgConfirmationResult {
        if (errorCode.isBlank()) {
            return PgConfirmationResult.unknown(errorCode, PgConfirmationResult.UnknownReason.INDETERMINATE)
        }
        if (errorCode == "ALREADY_PROCESSED_PAYMENT") {
            return PgConfirmationResult.unknown(errorCode, PgConfirmationResult.UnknownReason.ALREADY_PROCESSED)
        }
        if (httpStatus >= 500) {
            return PgConfirmationResult.unknown(errorCode, PgConfirmationResult.UnknownReason.INDETERMINATE)
        }
        if (errorCode in USER_DECLINE_CODES) {
            return PgConfirmationResult.declined(errorCode)
        }
        if (errorCode in MERCHANT_ERROR_CODES) {
            return PgConfirmationResult.unknown(errorCode, PgConfirmationResult.UnknownReason.MERCHANT_ERROR)
        }
        return PgConfirmationResult.unknown(errorCode, PgConfirmationResult.UnknownReason.INDETERMINATE)
    }
}
