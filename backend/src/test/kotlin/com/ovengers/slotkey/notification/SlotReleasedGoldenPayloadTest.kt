package com.ovengers.slotkey.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.notification.consumer.SlotReleasedHandler
import com.ovengers.slotkey.notification.consumer.SlotReleasedListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import java.nio.charset.StandardCharsets.UTF_8

class SlotReleasedGoldenPayloadTest {
    @ParameterizedTest(name = "{0} golden reaches handler")
    @CsvSource(
        "cancelled, a3fc500f-bc5d-4811-b46d-da5a1d649d80, 10",
        "force-cancelled, 40b95f5c-fa7d-4aa3-8b9d-06116ecfa17d, 10",
        "hold-expired, 7b33639f-2e4c-4d0a-9bad-7fbea97d47e9, 10",
    )
    fun onMessageForwardsGoldenIdentity(
        reason: String,
        eventId: String,
        reservationId: Long,
    ) {
        val path = "/golden/outbox/slot-released-$reason-payload.json"
        val resource = javaClass.getResourceAsStream(path)
        assertThat(resource).describedAs("Existing Outbox golden: %s", path).isNotNull()
        val payload = requireNotNull(resource).bufferedReader(UTF_8).use { it.readText() }
        val handler = mock(SlotReleasedHandler::class.java)

        SlotReleasedListener(handler, ObjectMapper()).onMessage(payload)

        verify(handler).handle(eventId, reservationId)
        verifyNoMoreInteractions(handler)
    }
}
