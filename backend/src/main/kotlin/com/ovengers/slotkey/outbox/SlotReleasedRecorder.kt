package com.ovengers.slotkey.outbox

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Service
class SlotReleasedRecorder(
    private val repository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    enum class Reason {
        CANCELLED,
        FORCE_CANCELLED,
        HOLD_EXPIRED,
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        reservationId: Long?,
        spaceId: Long?,
        reason: Reason?,
    ) {
        val eventId = UUID.randomUUID().toString()
        val now = LocalDateTime.now(clock)
        val payload =
            try {
                objectMapper.writeValueAsString(Payload(eventId, reservationId, requireNotNull(reason), now))
            } catch (exception: JsonProcessingException) {
                throw IllegalStateException("Cannot serialize SlotReleased event", exception)
            }
        // Flush earlier pending writes before the IDENTITY insert acquires the outbox lock.
        repository.flush()
        repository.save(OutboxEvent(eventId, spaceId, payload, now))
    }

    private data class Payload(
        val eventId: String,
        val reservationId: Long?,
        val reason: Reason,
        val occurredAt: LocalDateTime,
    )
}
