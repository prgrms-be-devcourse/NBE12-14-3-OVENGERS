package com.ovengers.slotkey.credit.pg

interface PgClient {
    fun confirm(request: PgConfirmRequest): PgConfirmationResult

    fun findByPaymentKey(paymentKey: String): PgPaymentLookupResult
}
