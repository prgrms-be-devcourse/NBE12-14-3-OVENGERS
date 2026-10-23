package com.ovengers.slotkey.credit.pg

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.Optional

@JvmRecord
data class PgPaymentLookupResult(
    val status: Status,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val paymentKey: Optional<String>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val orderId: Optional<String>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val totalAmount: Optional<Long>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val currency: Optional<String>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val rawStatus: Optional<String>,
    val paymentObjectReceived: Boolean,
) {
    enum class Status { APPROVED, FAILED, UNKNOWN }

    companion object {
        @JvmStatic
        fun approved(paymentKey: String): PgPaymentLookupResult =
            create(
                Status.APPROVED,
                Optional.of(paymentKey),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("DONE"),
                true,
            )

        @JvmStatic
        fun approved(
            paymentKey: Optional<String>,
            orderId: Optional<String>,
            totalAmount: Optional<Long>,
            currency: Optional<String>,
            rawStatus: Optional<String>,
        ): PgPaymentLookupResult = create(Status.APPROVED, paymentKey, orderId, totalAmount, currency, rawStatus, true)

        @JvmStatic
        fun failed(paymentKey: String): PgPaymentLookupResult =
            create(Status.FAILED, Optional.of(paymentKey), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), true)

        @JvmStatic
        fun failed(
            paymentKey: Optional<String>,
            orderId: Optional<String>,
            totalAmount: Optional<Long>,
            currency: Optional<String>,
            rawStatus: Optional<String>,
        ): PgPaymentLookupResult = create(Status.FAILED, paymentKey, orderId, totalAmount, currency, rawStatus, true)

        @JvmStatic
        fun unknown(): PgPaymentLookupResult =
            create(Status.UNKNOWN, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false)

        @JvmStatic
        fun unknown(
            paymentKey: Optional<String>,
            orderId: Optional<String>,
            totalAmount: Optional<Long>,
            currency: Optional<String>,
            rawStatus: Optional<String>,
        ): PgPaymentLookupResult = create(Status.UNKNOWN, paymentKey, orderId, totalAmount, currency, rawStatus, true)

        private fun create(
            status: Status,
            paymentKey: Optional<String>,
            orderId: Optional<String>,
            totalAmount: Optional<Long>,
            currency: Optional<String>,
            rawStatus: Optional<String>,
            paymentObjectReceived: Boolean,
        ): PgPaymentLookupResult =
            PgPaymentLookupResult(status, paymentKey, orderId, totalAmount, currency, rawStatus, paymentObjectReceived)
    }
}
