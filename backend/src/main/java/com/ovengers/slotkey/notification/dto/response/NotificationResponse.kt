package com.ovengers.slotkey.notification.dto.response

import com.ovengers.slotkey.notification.entity.Notification
import java.time.LocalDateTime

@JvmRecord
data class NotificationResponse(
    val id: Long,
    val waitlistEntryId: Long,
    val message: String,
    val read: Boolean,
    val createdAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(notification: Notification): NotificationResponse =
            NotificationResponse(
                notification.id,
                notification.waitlistEntryId,
                notification.message,
                notification.readAt != null,
                notification.createdAt,
            )
    }
}
