package com.ovengers.slotkey.outbox

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
@ConditionalOnProperty(name = ["slotkey.kafka.enabled"], havingValue = "true")
class KafkaOutboxPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${slotkey.kafka.send-timeout-ms}") private val sendTimeoutMs: Long,
) : OutboxPublisher {
    override fun publish(
        topic: String,
        key: String,
        payload: String,
    ) {
        try {
            kafkaTemplate.send(topic, key, payload).get(sendTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OutboxPublishException("Interrupted while sending to topic $topic", exception)
        } catch (exception: ExecutionException) {
            throw OutboxPublishException("Failed to send to topic $topic", exception)
        } catch (exception: TimeoutException) {
            throw OutboxPublishException("Failed to send to topic $topic", exception)
        }
    }
}
