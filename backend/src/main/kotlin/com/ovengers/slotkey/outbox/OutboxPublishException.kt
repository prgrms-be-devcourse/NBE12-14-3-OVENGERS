package com.ovengers.slotkey.outbox

open class OutboxPublishException : RuntimeException {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable) : super(message, cause)
}
