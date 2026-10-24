package com.ovengers.slotkey.notification.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/** `slot-released` 토픽 리스너. payload에서 eventId와 reservationId만 꺼내 핸들러에 넘긴다. */
@Component
@ConditionalOnProperty(name = ["slotkey.kafka.enabled"], havingValue = "true")
class SlotReleasedListener(
    private val handler: SlotReleasedHandler,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(topics = [TOPIC], groupId = GROUP_ID)
    fun onMessage(payload: String) {
        val node =
            try {
                objectMapper.readTree(payload)
            } catch (exception: Exception) {
                throw IllegalArgumentException("Unreadable SlotReleased payload", exception)
            }
        val eventId = node?.get("eventId")
        val reservationId = node?.get("reservationId")
        if (
            eventId == null ||
            !eventId.isTextual ||
            eventId.asText().isBlank() ||
            reservationId == null ||
            !reservationId.isIntegralNumber ||
            !reservationId.canConvertToLong()
        ) {
            throw IllegalArgumentException("SlotReleased payload needs eventId and reservationId")
        }
        handler.handle(eventId.asText(), reservationId.asLong())
    }

    companion object {
        const val TOPIC = "slot-released"
        const val GROUP_ID = "slotkey-waitlist-notifier"
    }
}
