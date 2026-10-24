package com.ovengers.slotkey.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

/**
 * PENDING outbox 행을 id 순으로 읽어 한 건씩 발행한다. 전달 보장은 최소 1회, 인스턴스 1개를 가정한다.
 * 후보 조회는 잠금 없는 SELECT이고 상태 변경은 PK 조건부 UPDATE(건별 트랜잭션)다. 이 클래스는 트랜잭션을 열지 않으므로
 * Kafka 전송 중에는 DB 잠금을 쥐지 않는다. (status, id) 범위에 FOR UPDATE를 쓰면 취소 트랜잭션과 락 대기가 생긴다.
 */
@Component
@ConditionalOnProperty(name = ["slotkey.kafka.enabled"], havingValue = "true")
class OutboxRelay(
    private val repository: OutboxEventRepository,
    private val publisher: OutboxPublisher,
    private val clock: Clock,
    @Value("\${slotkey.outbox.relay.batch-size:50}") private val batchSize: Int,
) {
    @Scheduled(
        fixedDelayString = "\${slotkey.outbox.relay.fixed-delay-ms:1000}",
        initialDelayString = "\${slotkey.outbox.relay.initial-delay-ms:0}",
    )
    fun relayPending() {
        repository.findByStatusOrderByIdAsc("PENDING", PageRequest.ofSize(batchSize)).forEach(::relayOne)
    }

    private fun relayOne(event: OutboxEvent) {
        try {
            publisher.publish(event.topic, event.partitionKey, event.payload)
        } catch (exception: RuntimeException) {
            logger.warn("outbox relay failed: id={}, eventId={}", event.id, event.eventId, exception)
            increaseAttempts(event)
            return
        }
        try {
            repository.markPublished(event.id, LocalDateTime.now(clock))
        } catch (exception: RuntimeException) {
            logger.warn("outbox relay sent but mark failed: id={}, eventId={}", event.id, event.eventId, exception)
            increaseAttempts(event)
        }
    }

    private fun increaseAttempts(event: OutboxEvent) {
        try {
            repository.increaseAttempts(event.id)
        } catch (exception: RuntimeException) {
            logger.warn("outbox attempts update failed: id={}", event.id, exception)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxRelay::class.java)
    }
}
