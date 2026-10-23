package com.ovengers.slotkey.credit.webhook

enum class PgWebhookEventResult {
    CONFIRMED,
    ALREADY_PAID,
    ORDER_PENDING,
    ORDER_FAILED,
    ORDER_NOT_FOUND,
    ORDER_INVARIANT_VIOLATION,
    PG_NOT_APPROVED,
    PG_MISMATCH,
    PG_LOOKUP_ERROR,
    STATE_CONFLICT,
    COMPLETE_ERROR,
}
