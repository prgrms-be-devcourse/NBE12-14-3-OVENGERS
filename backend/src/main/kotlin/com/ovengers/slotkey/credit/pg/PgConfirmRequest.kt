package com.ovengers.slotkey.credit.pg

@JvmRecord
data class PgConfirmRequest(
    val paymentKey: String,
    val orderId: String,
    val amount: Long,
    val idempotencyKey: String,
)
