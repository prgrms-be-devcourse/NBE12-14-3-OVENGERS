package com.ovengers.slotkey.credit.scheduler

@JvmRecord
data class ChargeOrderRecoveryTarget(
    val chargeOrderId: Long?,
    val orderId: String,
    val paymentKey: String,
    val clientIdempotencyKey: String,
    val amount: Long,
)
