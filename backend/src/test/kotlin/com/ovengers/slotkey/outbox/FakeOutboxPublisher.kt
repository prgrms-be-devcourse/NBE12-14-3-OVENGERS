package com.ovengers.slotkey.outbox

import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Predicate

/** CI용 발행 포트. 실제 Kafka 없이 relay의 성공·실패 경로를 만든다. */
class FakeOutboxPublisher : OutboxPublisher {
    @JvmRecord
    data class Sent(
        val topic: String,
        val key: String,
        val payload: String,
    )

    private val sent: MutableList<Sent> = CopyOnWriteArrayList()

    @Volatile
    private var failWhen = Predicate<String> { false }

    override fun publish(
        topic: String,
        key: String,
        payload: String,
    ) {
        if (failWhen.test(payload)) {
            throw OutboxPublishException("fake publish failure")
        }
        sent.add(Sent(topic, key, payload))
    }

    fun failWhen(condition: Predicate<String>) {
        failWhen = condition
    }

    fun reset() {
        sent.clear()
        failWhen = Predicate { false }
    }

    fun sent(): MutableList<Sent> = sent
}
