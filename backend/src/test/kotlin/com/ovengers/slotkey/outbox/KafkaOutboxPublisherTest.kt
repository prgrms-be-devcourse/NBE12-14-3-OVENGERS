package com.ovengers.slotkey.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class KafkaOutboxPublisherTest {
    @Suppress("UNCHECKED_CAST")
    private val template = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
    private val publisher = KafkaOutboxPublisher(template, 100)

    @Test
    fun sendsWithRowTopicAndPartitionKey() {
        `when`(template.send("slot-released", "7", "{}")).thenReturn(CompletableFuture.completedFuture(null))

        publisher.publish("slot-released", "7", "{}")

        verify(template).send("slot-released", "7", "{}")
    }

    @Test
    fun brokerFailureIsRaisedAsException() {
        val failed = CompletableFuture<SendResult<String, String>>()
        failed.completeExceptionally(IllegalStateException("broker down"))
        `when`(template.send("slot-released", "7", "{}")).thenReturn(failed)

        assertThatThrownBy { publisher.publish("slot-released", "7", "{}") }
            .isInstanceOf(OutboxPublishException::class.java)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun interruptRestoresFlagAndRaisesException() {
        val future = mock(CompletableFuture::class.java) as CompletableFuture<SendResult<String, String>>
        `when`(future.get(100L, TimeUnit.MILLISECONDS)).thenThrow(InterruptedException())
        `when`(template.send("slot-released", "7", "{}")).thenReturn(future)

        try {
            assertThatThrownBy { publisher.publish("slot-released", "7", "{}") }
                .isInstanceOf(OutboxPublishException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted() // 다른 테스트에 플래그가 새지 않게 비운다
        }
    }

    @Test
    fun sendWaitsOnlyUpToTimeout() {
        `when`(template.send("slot-released", "7", "{}")).thenReturn(CompletableFuture())

        val start = System.nanoTime()
        assertThatThrownBy { publisher.publish("slot-released", "7", "{}") }
            .isInstanceOf(OutboxPublishException::class.java)

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000)
    }
}
