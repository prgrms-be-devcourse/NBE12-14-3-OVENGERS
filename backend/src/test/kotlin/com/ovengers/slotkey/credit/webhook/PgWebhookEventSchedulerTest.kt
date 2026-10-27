package com.ovengers.slotkey.credit.webhook

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class PgWebhookEventSchedulerTest {
    @Mock
    private lateinit var eventStore: PgWebhookEventStore

    @Mock
    private lateinit var processor: PgWebhookEventProcessor
    private lateinit var scheduler: PgWebhookEventScheduler

    @BeforeEach
    fun setUp() {
        scheduler = PgWebhookEventScheduler(eventStore, processor, BATCH_SIZE)
    }

    @Test
    @DisplayName("RECEIVED 행을 배치 크기만큼 읽어 한 건씩 처리한다")
    fun processReceivedEvents_processesEachReceivedEvent() {
        given(eventStore.findReceivedIds(BATCH_SIZE)).willReturn(listOf(1L, 2L, 3L))

        scheduler.processReceivedEvents()

        verify(processor).process(1L)
        verify(processor).process(2L)
        verify(processor).process(3L)
    }

    @Test
    @DisplayName("한 건의 예외가 뒤 행 처리를 막지 않는다")
    fun processReceivedEvents_oneFailure_doesNotStopRemaining() {
        given(eventStore.findReceivedIds(BATCH_SIZE)).willReturn(listOf(1L, 2L))
        doThrow(IllegalStateException("db down")).`when`(processor).process(1L)

        scheduler.processReceivedEvents()

        verify(processor).process(2L)
    }

    @Test
    @DisplayName("처리할 행이 없으면 처리기를 호출하지 않는다")
    fun processReceivedEvents_empty_doesNothing() {
        given(eventStore.findReceivedIds(BATCH_SIZE)).willReturn(listOf())

        scheduler.processReceivedEvents()

        verify(processor, never()).process(anyLong())
    }

    companion object {
        private const val BATCH_SIZE = 50
    }
}
