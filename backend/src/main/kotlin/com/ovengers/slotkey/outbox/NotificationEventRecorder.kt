package com.ovengers.slotkey.outbox

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.notification.entity.NotificationType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

/**
 * `notification-events` 토픽용 outbox 기록기. 호출자 트랜잭션 안에서만 INSERT하므로 롤백되면 이벤트도 사라진다.
 * `recipientMemberId`가 null이면 컨슈머가 ACTIVE 관리자 전원에게 알림을 만든다(partition_key는 "ADMIN").
 */
@Service
class NotificationEventRecorder(
    private val repository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        type: NotificationType,
        recipientMemberId: Long?,
        referenceId: Long,
        message: String,
    ) {
        require(type != NotificationType.WAITLIST_SLOT_RELEASED) { "WAITLIST_SLOT_RELEASED uses the slot-released topic" }
        val eventId = UUID.randomUUID().toString()
        val now = LocalDateTime.now(clock)
        val payload =
            try {
                objectMapper.writeValueAsString(Payload(eventId, type, recipientMemberId, referenceId, message, now))
            } catch (exception: JsonProcessingException) {
                throw IllegalStateException("Cannot serialize notification event", exception)
            }
        // Flush earlier pending writes before the IDENTITY insert acquires the outbox lock.
        repository.flush()
        repository.save(OutboxEvent(eventId, type.name, TOPIC, recipientMemberId?.toString() ?: ADMIN_KEY, payload, now))
    }

    private data class Payload(
        val eventId: String,
        val eventType: NotificationType,
        val recipientMemberId: Long?,
        val referenceId: Long,
        val message: String,
        val occurredAt: LocalDateTime,
    )

    companion object {
        const val TOPIC = "notification-events"
        const val ADMIN_KEY = "ADMIN"
    }
}
