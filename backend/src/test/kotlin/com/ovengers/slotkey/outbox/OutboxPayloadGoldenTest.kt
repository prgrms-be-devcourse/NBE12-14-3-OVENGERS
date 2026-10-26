package com.ovengers.slotkey.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.support.IntegrationTestSupport
import com.ovengers.slotkey.support.SurveyGoldenAssertions.readGolden
import com.ovengers.slotkey.support.SurveyGoldenClockConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

@Import(SurveyGoldenClockConfig::class)
@Transactional
class OutboxPayloadGoldenTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var recorder: SlotReleasedRecorder

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    @ParameterizedTest
    @EnumSource(SlotReleasedRecorder.Reason::class)
    fun recordedPayloadKeepsJavaFields(reason: SlotReleasedRecorder.Reason) {
        recorder.record(10L, 20L, reason)
        val rows = jdbc.queryForList("SELECT * FROM outbox_event WHERE partition_key = '20'")
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["event_type"]).isEqualTo("SlotReleased")
        assertThat(row["topic"]).isEqualTo("slot-released")
        assertThat(row["partition_key"]).isEqualTo("20")
        assertThat(row["status"]).isEqualTo("PENDING")
        assertThat(row["attempts"]).isEqualTo(0)
        assertThat(row["published_at"]).isNull()
        val actual = mapper.readTree(row["payload"].toString())
        val situation = reason.name.lowercase(Locale.ROOT).replace('_', '-')
        val expected = readGolden(mapper, "outbox/slot-released-$situation-payload")
        assertThat(actual.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrderElementsOf(expected.fieldNames().asSequence().toList())
        // UUID is generated inside the recorder. Check the field itself; do not rewrite JSON.
        assertThat(actual["eventId"].isTextual).isTrue()
        assertThat(actual["eventId"].textValue()).isEqualTo(row["event_id"])
        val eventId = UUID.fromString(actual["eventId"].textValue())
        assertThat(eventId.toString()).isEqualTo(actual["eventId"].textValue())
        assertThat(eventId.version()).isEqualTo(4)
        assertThat(eventId.variant()).isEqualTo(2)
        for (field in listOf("reservationId", "reason", "occurredAt")) {
            assertThat(actual[field]).describedAs(field).isEqualTo(expected[field])
        }
        assertThat(actual["reservationId"].isIntegralNumber).isTrue()
        assertThat(LocalDateTime.parse(actual["occurredAt"].textValue()))
            .isEqualTo(LocalDateTime.of(2026, 10, 23, 9, 0))
    }
}
