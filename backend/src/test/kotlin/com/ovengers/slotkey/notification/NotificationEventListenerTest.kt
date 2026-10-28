package com.ovengers.slotkey.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.notification.consumer.NotificationEventHandler
import com.ovengers.slotkey.notification.consumer.NotificationEventListener
import com.ovengers.slotkey.notification.entity.NotificationType
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

/** 리스너는 payload를 검증해 핸들러에 넘기는 얇은 껍데기다(Kafka 없이 직접 호출). */
class NotificationEventListenerTest {
    private val handler = mock(NotificationEventHandler::class.java)
    private val listener = NotificationEventListener(handler, ObjectMapper())

    @Test
    fun memberPayloadIsPassedToHandler() {
        listener.onMessage(
            "{\"eventId\":\"e-1\",\"eventType\":\"CREDIT_GRANTED\",\"recipientMemberId\":41,\"referenceId\":7," +
                "\"message\":\"지급\",\"occurredAt\":\"2026-10-28T12:00:00\"}",
        )

        verify(handler).handle("e-1", NotificationType.CREDIT_GRANTED, 41L, 7L, "지급")
    }

    @Test
    fun nullRecipientMeansAdminBroadcast() {
        listener.onMessage(
            "{\"eventId\":\"e-2\",\"eventType\":\"INQUIRY_CREATED\",\"recipientMemberId\":null,\"referenceId\":9,\"message\":\"새 문의\"}",
        )

        verify(handler).handle("e-2", NotificationType.INQUIRY_CREATED, null, 9L, "새 문의")
    }

    @Test
    fun unknownFieldsAreIgnored() {
        listener.onMessage(
            "{\"eventId\":\"e-3\",\"eventType\":\"INQUIRY_ANSWERED\",\"recipientMemberId\":1,\"referenceId\":2,\"message\":\"m\",\"future\":{\"a\":1}}",
        )

        verify(handler).handle("e-3", NotificationType.INQUIRY_ANSWERED, 1L, 2L, "m")
    }

    @Test
    fun unknownOrWaitlistEventTypeIsSkippedWithoutError() {
        assertThatCode {
            listener.onMessage(
                "{\"eventId\":\"e\",\"eventType\":\"SOMETHING_NEW\",\"recipientMemberId\":1,\"referenceId\":2,\"message\":\"m\"}",
            )
            listener.onMessage(
                "{\"eventId\":\"e\",\"eventType\":\"WAITLIST_SLOT_RELEASED\",\"recipientMemberId\":1,\"referenceId\":2,\"message\":\"m\"}",
            )
        }.doesNotThrowAnyException()

        verifyNoInteractions(handler)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
        strings = [
            "not-json",
            "",
            "[]",
            "{\"eventType\":\"CREDIT_GRANTED\",\"recipientMemberId\":1,\"referenceId\":2,\"message\":\"m\"}",
            "{\"eventId\":\" \",\"eventType\":\"CREDIT_GRANTED\",\"recipientMemberId\":1,\"referenceId\":2,\"message\":\"m\"}",
            "{\"eventId\":\"e\",\"recipientMemberId\":1,\"referenceId\":2,\"message\":\"m\"}",
            "{\"eventId\":\"e\",\"eventType\":\"CREDIT_GRANTED\",\"recipientMemberId\":\"1\",\"referenceId\":2,\"message\":\"m\"}",
            "{\"eventId\":\"e\",\"eventType\":\"CREDIT_GRANTED\",\"recipientMemberId\":1,\"message\":\"m\"}",
            "{\"eventId\":\"e\",\"eventType\":\"CREDIT_GRANTED\",\"recipientMemberId\":1,\"referenceId\":1.5,\"message\":\"m\"}",
            "{\"eventId\":\"e\",\"eventType\":\"CREDIT_GRANTED\",\"recipientMemberId\":1,\"referenceId\":2}",
            "{\"eventId\":\"e\",\"eventType\":\"CREDIT_GRANTED\",\"recipientMemberId\":1,\"referenceId\":2,\"message\":\"\"}",
        ],
    )
    fun malformedPayloadIsRejected(payload: String) {
        assertThatThrownBy { listener.onMessage(payload) }.isInstanceOf(IllegalArgumentException::class.java)

        verifyNoInteractions(handler)
    }
}
