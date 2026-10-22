package com.ovengers.slotkey.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** relay의 실패 분기를 저장소·발행 포트 mock으로 검증한다(DB 없음). */
class OutboxRelayTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private final OutboxRelay relay = new OutboxRelay(repository, publisher, Clock.systemDefaultZone(), 50);

    private OutboxEvent event(long id) {
        OutboxEvent event = new OutboxEvent("e-" + id, 1L, "{\"id\":" + id + "}", LocalDateTime.now());
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }

    private void pending(OutboxEvent... events) {
        when(repository.findByStatusOrderByIdAsc(eq("PENDING"), any(Pageable.class))).thenReturn(List.of(events));
    }

    @Test
    void markFailureAfterSuccessfulSendCountsAttemptAndContinues() {
        pending(event(1), event(2));
        doThrow(new IllegalStateException("db down")).when(repository).markPublished(eq(1L), any());

        relay.relayPending();

        verify(repository).increaseAttempts(1L);
        verify(publisher).publish("slot-released", "1", "{\"id\":2}");
        verify(repository).markPublished(eq(2L), any());
    }

    @Test
    void attemptsUpdateFailureDoesNotStopTheRound() {
        pending(event(1), event(2));
        doThrow(new OutboxPublishException("kafka down")).when(publisher).publish(any(), any(), eq("{\"id\":1}"));
        doThrow(new IllegalStateException("db down")).when(repository).increaseAttempts(1L);

        relay.relayPending();

        verify(repository).markPublished(eq(2L), any());
    }

    @Test
    void nothingPendingMeansNoPublishAndNoUpdate() {
        pending();

        relay.relayPending();

        verify(publisher, never()).publish(any(), any(), any());
        verify(repository, never()).markPublished(any(), any());
        verify(repository, never()).increaseAttempts(any());
    }
}
