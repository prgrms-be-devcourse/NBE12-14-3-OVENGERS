package com.ovengers.slotkey.member.dto.response

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberStatus
import java.time.LocalDateTime

@JvmRecord
data class MemberStatusChangeResponse(
    val memberId: Long,
    val status: MemberStatus,
    val reason: String,
    val changedAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun of(
            member: Member,
            reason: String,
            changedAt: LocalDateTime,
        ) = MemberStatusChangeResponse(
            member.getId(),
            member.getStatus(),
            reason,
            changedAt,
        )
    }
}
