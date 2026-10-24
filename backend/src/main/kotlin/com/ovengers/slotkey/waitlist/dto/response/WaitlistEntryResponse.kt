package com.ovengers.slotkey.waitlist.dto.response

import com.ovengers.slotkey.waitlist.entity.WaitlistEntry
import com.ovengers.slotkey.waitlist.entity.WaitlistStatus
import java.time.LocalDateTime

@JvmRecord
data class WaitlistEntryResponse(
    val id: Long,
    val spaceId: Long,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: WaitlistStatus,
    val position: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun of(
            entry: WaitlistEntry,
            position: Long,
        ): WaitlistEntryResponse =
            WaitlistEntryResponse(
                requireNotNull(entry.id),
                entry.spaceId,
                entry.startTime,
                entry.endTime,
                entry.status,
                position,
                entry.createdAt,
            )
    }
}
