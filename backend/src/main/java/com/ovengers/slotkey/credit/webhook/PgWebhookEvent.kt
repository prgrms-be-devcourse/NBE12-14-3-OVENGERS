package com.ovengers.slotkey.credit.webhook

import com.fasterxml.jackson.annotation.JsonInclude

/** 웹훅 본문에서 꺼낸 기록용 힌트이며 충전 확정 근거로 사용하지 않는다. */
@JvmRecord
data class PgWebhookEvent(
    val transmissionId: String,
    val eventType: String,
    val orderId: String,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val paymentKey: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val reportedStatus: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val retriedCount: Int?,
)
