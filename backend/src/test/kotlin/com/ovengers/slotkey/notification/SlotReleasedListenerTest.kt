package com.ovengers.slotkey.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.notification.consumer.SlotReleasedHandler
import com.ovengers.slotkey.notification.consumer.SlotReleasedListener
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

/** 리스너는 payload에서 eventId·reservationId만 꺼내 핸들러에 넘기는 얇은 껍데기다(Kafka 없이 직접 호출). */
class SlotReleasedListenerTest {
    private val handler = mock(SlotReleasedHandler::class.java)
    private val listener = SlotReleasedListener(handler, ObjectMapper())

    @Test
    @DisplayName("실제 발행 payload(4필드)에서 eventId와 reservationId를 꺼내 핸들러를 호출한다")
    fun onMessage_validPayload_callsHandler() {
        listener.onMessage(
            "{\"eventId\":\"e-1\",\"reservationId\":42,\"reason\":\"CANCELLED\"," +
                "\"occurredAt\":\"2026-10-16T12:00:00\"}",
        )

        verify(handler).handle("e-1", 42L)
    }

    @Test
    @DisplayName("JSON이 아니거나 필수 필드가 없으면 IllegalArgumentException이고 핸들러를 호출하지 않는다")
    fun onMessage_malformedPayload_throwsAndSkipsHandler() {
        assertThatThrownBy { listener.onMessage("not-json") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { listener.onMessage("{\"reservationId\":1}") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { listener.onMessage("{\"eventId\":\"e\"}") }
            .isInstanceOf(IllegalArgumentException::class.java)

        verifyNoInteractions(handler)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
        strings = [
            "{\"eventId\":123,\"reservationId\":1}",
            "{\"eventId\":null,\"reservationId\":1}",
            "{\"eventId\":\"\",\"reservationId\":1}",
            "{\"eventId\":\"  \",\"reservationId\":1}",
            "{\"eventId\":\"e\",\"reservationId\":\"7\"}",
            "{\"eventId\":\"e\",\"reservationId\":null}",
            "{\"eventId\":\"e\",\"reservationId\":1.5}",
            "{\"eventId\":\"e\",\"reservationId\":99999999999999999999}",
            "[]",
            "\"text\"",
            "",
        ],
    )
    @DisplayName("eventId·reservationId의 타입·값이 어긋난 payload는 모두 IllegalArgumentException이고 핸들러를 호출하지 않는다")
    fun onMessage_invalidFieldShapes_rejected(payload: String) {
        assertThatThrownBy { listener.onMessage(payload) }.isInstanceOf(IllegalArgumentException::class.java)

        verifyNoInteractions(handler)
    }

    @Test
    @DisplayName("모르는 필드가 더 있어도(앞으로 payload가 늘어도) 필요한 두 필드만 읽어 처리한다")
    fun onMessage_extraFields_ignored() {
        listener.onMessage("{\"eventId\":\"e-2\",\"reservationId\":5,\"spaceId\":9,\"future\":{\"a\":1}}")

        verify(handler).handle("e-2", 5L)
    }
}
