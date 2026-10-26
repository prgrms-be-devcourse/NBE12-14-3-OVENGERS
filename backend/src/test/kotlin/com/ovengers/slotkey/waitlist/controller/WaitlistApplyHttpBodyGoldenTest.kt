package com.ovengers.slotkey.waitlist.controller

import com.ovengers.slotkey.support.HttpBodyGoldenSupport
import com.ovengers.slotkey.support.SurveyGoldenAssertions.readGolden
import com.ovengers.slotkey.waitlist.dto.response.WaitlistEntryResponse
import com.ovengers.slotkey.waitlist.entity.WaitlistStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

class WaitlistApplyHttpBodyGoldenTest : HttpBodyGoldenSupport() {
    @Test
    fun waitingReturns201WithJavaBody() {
        val start = LocalDateTime.of(2026, 10, 20, 14, 0)
        val end = start.plusHours(1)
        given(waitlist.apply(7L, 3L, start, end)).willReturn(
            WaitlistEntryResponse(11L, 3L, start, end, WaitlistStatus.WAITING, 2L, LocalDateTime.of(2026, 10, 15, 9, 0)),
        )

        val request =
            post("/api/v1/waitlist")
                .header("Authorization", authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"spaceId":3,"startTime":"2026-10-20T14:00:00","endTime":"2026-10-20T15:00:00"}""")
        val response =
            mvc
                .perform(request)
                .andExpect(status().isCreated())
                .andReturn()
                .response

        assertThat(mapper.readTree(response.contentAsByteArray))
            .isEqualTo(readGolden(mapper, "waitlist/apply-waiting-created"))
        verify(waitlist).apply(7L, 3L, start, end)
    }
}
