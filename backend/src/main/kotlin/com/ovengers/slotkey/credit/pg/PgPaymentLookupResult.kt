package com.ovengers.slotkey.credit.pg

import com.fasterxml.jackson.annotation.JsonInclude

@JvmRecord
data class PgPaymentLookupResult(
    val status: Status,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val paymentKey: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val orderId: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val totalAmount: Long?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val currency: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val rawStatus: String?,
    val paymentObjectReceived: Boolean,
) {
    enum class Status { APPROVED, FAILED, UNKNOWN }

    companion object {
        @JvmStatic
        fun approved(paymentKey: String): PgPaymentLookupResult =
            create(
                Status.APPROVED,
                paymentKey,
                null,
                null,
                null,
                "DONE",
                true,
            )

        @JvmStatic
        fun approved(
            paymentKey: String?,
            orderId: String?,
            totalAmount: Long?,
            currency: String?,
            rawStatus: String?,
        ): PgPaymentLookupResult = create(Status.APPROVED, paymentKey, orderId, totalAmount, currency, rawStatus, true)

        @JvmStatic
        fun failed(paymentKey: String): PgPaymentLookupResult = create(Status.FAILED, paymentKey, null, null, null, null, true)

        @JvmStatic
        fun failed(
            paymentKey: String?,
            orderId: String?,
            totalAmount: Long?,
            currency: String?,
            rawStatus: String?,
        ): PgPaymentLookupResult = create(Status.FAILED, paymentKey, orderId, totalAmount, currency, rawStatus, true)

        @JvmStatic
        fun unknown(): PgPaymentLookupResult = create(Status.UNKNOWN, null, null, null, null, null, false)

        @JvmStatic
        fun unknown(
            paymentKey: String?,
            orderId: String?,
            totalAmount: Long?,
            currency: String?,
            rawStatus: String?,
        ): PgPaymentLookupResult = create(Status.UNKNOWN, paymentKey, orderId, totalAmount, currency, rawStatus, true)

        private fun create(
            status: Status,
            paymentKey: String?,
            orderId: String?,
            totalAmount: Long?,
            currency: String?,
            rawStatus: String?,
            paymentObjectReceived: Boolean,
        ): PgPaymentLookupResult =
            PgPaymentLookupResult(status, paymentKey, orderId, totalAmount, currency, rawStatus, paymentObjectReceived)
    }
}
