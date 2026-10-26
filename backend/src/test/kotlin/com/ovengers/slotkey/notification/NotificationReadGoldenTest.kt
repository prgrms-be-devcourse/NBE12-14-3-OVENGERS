package com.ovengers.slotkey.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.notification.dto.response.NotificationResponse
import com.ovengers.slotkey.notification.entity.Notification
import com.ovengers.slotkey.support.SurveyGoldenAssertions.assertGolden
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

@JsonTest
class NotificationReadGoldenTest {
    @Autowired
    private lateinit var mapper: ObjectMapper

    @Test
    fun responseKeepsJavaFactoryOutput() {
        val created = LocalDateTime.of(2026, 10, 23, 9, 0)
        val notification = Notification(1L, 3L, "00000000-0000-4000-8000-000000000001", "빈자리가 생겼습니다.", created)
        ReflectionTestUtils.setField(notification, "id", 5L)
        ReflectionTestUtils.setField(notification, "readAt", created.plusMinutes(1))
        assertGolden(mapper, "notification/read-true-response", NotificationResponse.from(notification))
    }
}
