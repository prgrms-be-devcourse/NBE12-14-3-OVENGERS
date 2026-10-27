package com.ovengers.slotkey.outbox

import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.eqNonNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.Pageable
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.LocalDateTime

/** relay의 실패 분기를 저장소·발행 포트 mock으로 검증한다(DB 없음). */
class OutboxRelayTest {
    private val repository = mock(OutboxEventRepository::class.java)
    private val publisher = mock(OutboxPublisher::class.java)
    private val relay = OutboxRelay(repository, publisher, Clock.systemDefaultZone(), 50)

    private fun event(id: Long): OutboxEvent {
        val event = OutboxEvent("e-" + id, 1L, "{\"id\":" + id + "}", LocalDateTime.now())
        ReflectionTestUtils.setField(event, "id", id)
        return event
    }

    private fun pending(vararg events: OutboxEvent) {
        `when`(repository.findByStatusOrderByIdAsc(eqNonNull("PENDING"), anyNonNull(Pageable::class.java)))
            .thenReturn(listOf(*events))
    }

    @Test
    fun markFailureAfterSuccessfulSendCountsAttemptAndContinues() {
        pending(event(1), event(2))
        doThrow(IllegalStateException("db down")).`when`(repository).markPublished(eq(1L), any())

        relay.relayPending()

        verify(repository).increaseAttempts(1L)
        verify(publisher).publish("slot-released", "1", "{\"id\":2}")
        verify(repository).markPublished(eq(2L), any())
    }

    @Test
    fun attemptsUpdateFailureDoesNotStopTheRound() {
        pending(event(1), event(2))
        doThrow(OutboxPublishException("kafka down"))
            .`when`(publisher)
            .publish(anyNonNull(), anyNonNull(), eqNonNull("{\"id\":1}"))
        doThrow(IllegalStateException("db down")).`when`(repository).increaseAttempts(1L)

        relay.relayPending()

        verify(repository).markPublished(eq(2L), any())
    }

    @Test
    fun nothingPendingMeansNoPublishAndNoUpdate() {
        pending()

        relay.relayPending()

        verify(publisher, never()).publish(anyNonNull(), anyNonNull(), anyNonNull())
        verify(repository, never()).markPublished(any(), any())
        verify(repository, never()).increaseAttempts(any())
    }
}
