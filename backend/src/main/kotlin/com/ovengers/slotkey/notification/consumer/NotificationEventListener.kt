package com.ovengers.slotkey.notification.consumer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.notification.entity.NotificationType
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `notification-events` 토픽 리스너. payload를 검증해 핸들러에 넘긴다.
 * 모르는 eventType은 경고만 남기고 건너뛴다(생산자가 먼저 배포돼도 컨슈머가 멈추지 않게 한다).
 */
@Component
@ConditionalOnProperty(name = ["slotkey.kafka.enabled"], havingValue = "true")
class NotificationEventListener(
    private val handler: NotificationEventHandler,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(topics = [TOPIC], groupId = GROUP_ID)
    fun onMessage(payload: String) {
        val node =
            try {
                objectMapper.readTree(payload)
            } catch (exception: Exception) {
                throw IllegalArgumentException("Unreadable notification event payload", exception)
            }
        val eventId = requireText(node, "eventId")
        val rawType = requireText(node, "eventType")
        val referenceId = requireLong(node, "referenceId")
        val message = requireText(node, "message")
        val recipient = recipientOf(node)

        val type = NotificationType.entries.firstOrNull { it.name == rawType && it != NotificationType.WAITLIST_SLOT_RELEASED }
        if (type == null) {
            logger.warn("Unsupported notification event type skipped: eventId={}, eventType={}", eventId, rawType)
            return
        }
        handler.handle(eventId, type, recipient, referenceId, message)
    }

    private fun requireText(
        node: JsonNode?,
        field: String,
    ): String {
        val value = node?.get(field)
        require(value != null && value.isTextual && value.asText().isNotBlank()) { "Notification event needs text field $field" }
        return value.asText()
    }

    private fun requireLong(
        node: JsonNode?,
        field: String,
    ): Long {
        val value = node?.get(field)
        require(value != null && value.isIntegralNumber && value.canConvertToLong()) { "Notification event needs integer field $field" }
        return value.asLong()
    }

    /** 키가 없으면 거부한다. 키가 있고 null일 때만 관리자 전원 대상이다. */
    private fun recipientOf(node: JsonNode?): Long? {
        require(node != null && node.has("recipientMemberId")) { "Notification event needs recipientMemberId" }
        val value = node.get("recipientMemberId")
        if (value.isNull) return null
        return requireLong(node, "recipientMemberId")
    }

    companion object {
        const val TOPIC = "notification-events"
        const val GROUP_ID = "slotkey-notifier"
        private val logger = LoggerFactory.getLogger(NotificationEventListener::class.java)
    }
}
