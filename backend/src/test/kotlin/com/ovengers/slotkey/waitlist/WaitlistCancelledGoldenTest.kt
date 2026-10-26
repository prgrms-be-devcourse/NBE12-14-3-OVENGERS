package com.ovengers.slotkey.waitlist

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.support.SurveyGoldenAssertions.assertGolden
import com.ovengers.slotkey.waitlist.dto.response.WaitlistEntryResponse
import com.ovengers.slotkey.waitlist.entity.WaitlistEntry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

@JsonTest
class WaitlistCancelledGoldenTest {
    @Autowired
    private lateinit var mapper: ObjectMapper

    @Test
    fun responseKeepsJavaFactoryOutput() {
        val created = LocalDateTime.of(2026, 10, 23, 9, 0)
        val start = LocalDateTime.of(2026, 10, 25, 10, 0)
        val entry = WaitlistEntry.waiting(1L, 20L, start, start.plusHours(1), created)
        ReflectionTestUtils.setField(entry, "id", 3L)
        entry.cancel()
        assertGolden(mapper, "waitlist/entry-cancelled-response", WaitlistEntryResponse.of(entry, 0L))
    }
}
