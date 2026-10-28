package com.ovengers.slotkey.notification

import com.ovengers.slotkey.notification.dto.response.NotificationResponse
import com.ovengers.slotkey.notification.entity.NotificationType
import com.ovengers.slotkey.support.HttpBodyGoldenSupport
import com.ovengers.slotkey.support.SurveyGoldenAssertions.readGolden
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

class NotificationListHttpBodyGoldenTest : HttpBodyGoldenSupport() {
    @Test
    fun listReturnsOrderedNotificationsAndJavaPageBody() {
        val pageable = PageRequest.of(1, 2)
        val created = LocalDateTime.of(2026, 10, 16, 12, 0)
        given(notifications.getMyNotifications(7L, pageable)).willReturn(
            PageImpl(
                listOf(
                    NotificationResponse(2L, 12L, NotificationType.WAITLIST_SLOT_RELEASED, 12L, "두 번째 알림", true, created.plusMinutes(1)),
                    NotificationResponse(1L, 11L, NotificationType.WAITLIST_SLOT_RELEASED, 11L, "자리가 비었습니다", false, created),
                ),
                pageable,
                5L,
            ),
        )

        val request =
            get("/api/v1/notifications")
                .param("page", "1")
                .param("size", "2")
                .header("Authorization", authorization())
        val response =
            mvc
                .perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .response

        assertThat(mapper.readTree(response.contentAsByteArray))
            .isEqualTo(readGolden(mapper, "notification/list-nonfirst-page-ok"))
        verify(notifications).getMyNotifications(7L, pageable)
    }
}
