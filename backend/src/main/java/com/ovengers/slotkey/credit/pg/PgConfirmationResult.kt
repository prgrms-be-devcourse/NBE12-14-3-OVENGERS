package com.ovengers.slotkey.credit.pg

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.Optional

@JvmRecord
data class PgConfirmationResult(
    val type: Type,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val paymentKey: Optional<String>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val errorCode: Optional<String>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val unknownReason: Optional<UnknownReason>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val paymentMethod: Optional<String>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val paymentProvider: Optional<String>,
) {
    enum class Type { APPROVED, DECLINED, UNKNOWN }

    enum class UnknownReason { ALREADY_PROCESSED, MERCHANT_ERROR, INDETERMINATE }

    companion object {
        @JvmStatic
        fun approved(paymentKey: String): PgConfirmationResult = approved(Optional.of(paymentKey), Optional.empty(), Optional.empty())

        @JvmStatic
        fun approved(
            paymentKey: Optional<String>,
            paymentMethod: Optional<String>,
            paymentProvider: Optional<String>,
        ): PgConfirmationResult =
            PgConfirmationResult(
                Type.APPROVED,
                paymentKey,
                Optional.empty(),
                Optional.empty(),
                paymentMethod,
                paymentProvider,
            )

        @JvmStatic
        fun declined(errorCode: String): PgConfirmationResult =
            PgConfirmationResult(
                Type.DECLINED,
                Optional.empty(),
                Optional.of(errorCode),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
            )

        @JvmStatic
        fun unknown(
            errorCode: String,
            reason: UnknownReason,
        ): PgConfirmationResult = unknown(Optional.of(errorCode), reason)

        @JvmStatic
        fun unknown(
            errorCode: Optional<String>,
            reason: UnknownReason,
        ): PgConfirmationResult =
            PgConfirmationResult(
                Type.UNKNOWN,
                Optional.empty(),
                errorCode,
                Optional.of(reason),
                Optional.empty(),
                Optional.empty(),
            )
    }
}
