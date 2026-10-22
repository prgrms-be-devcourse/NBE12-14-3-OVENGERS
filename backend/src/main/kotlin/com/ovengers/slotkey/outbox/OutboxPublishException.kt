package com.ovengers.slotkey.outbox

open class OutboxPublishException(
    message: String?,
    cause: Throwable?,
) : RuntimeException(message, cause)
