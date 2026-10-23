package com.ovengers.slotkey.credit.pg

import com.fasterxml.jackson.annotation.JsonInclude

@JvmRecord
data class PgConfirmationResult(
    val type: Type,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val paymentKey: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val errorCode: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val unknownReason: UnknownReason?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val paymentMethod: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val paymentProvider: String?,
) {
    enum class Type { APPROVED, DECLINED, UNKNOWN }

    enum class UnknownReason { ALREADY_PROCESSED, MERCHANT_ERROR, INDETERMINATE }

    companion object {
        @JvmStatic
        fun approved(paymentKey: String): PgConfirmationResult = approved(paymentKey, null, null)

        @JvmStatic
        fun approved(
            paymentKey: String?,
            paymentMethod: String?,
            paymentProvider: String?,
        ): PgConfirmationResult =
            PgConfirmationResult(
                Type.APPROVED,
                paymentKey,
                null,
                null,
                paymentMethod,
                paymentProvider,
            )

        @JvmStatic
        fun declined(errorCode: String): PgConfirmationResult =
            PgConfirmationResult(
                Type.DECLINED,
                null,
                errorCode,
                null,
                null,
                null,
            )

        @JvmStatic
        fun unknown(
            errorCode: String?,
            reason: UnknownReason,
        ): PgConfirmationResult =
            PgConfirmationResult(
                Type.UNKNOWN,
                null,
                errorCode,
                reason,
                null,
                null,
            )
    }
}
