package com.ovengers.slotkey.outbox

/** outbox 행을 외부(Kafka)로 내보내는 포트. 전송이 확인되지 않으면 [OutboxPublishException]을 던진다. */
fun interface OutboxPublisher {
    fun publish(
        topic: String,
        key: String,
        payload: String,
    )
}
