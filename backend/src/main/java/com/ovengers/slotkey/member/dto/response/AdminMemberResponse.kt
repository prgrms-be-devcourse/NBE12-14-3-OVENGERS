package com.ovengers.slotkey.member.dto.response

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import java.time.LocalDateTime

@JvmRecord
data class AdminMemberResponse(
    val memberId: Long,
    val email: String,
    val nickname: String,
    val role: MemberRole,
    val status: MemberStatus,
    val balance: Int,
    val createdAt: LocalDateTime,
    val lastStatusChangedAt: LocalDateTime?,
    val lastStatusChangeReason: String?,
) {
    companion object {
        @JvmStatic
        fun of(
            member: Member,
            lastStatusChangedAt: LocalDateTime?,
            lastStatusChangeReason: String?,
        ) = AdminMemberResponse(
            member.getId(),
            member.getEmail(),
            member.getNickname(),
            member.getRole(),
            member.getStatus(),
            member.getBalance(),
            requireNotNull(member.createdAt),
            lastStatusChangedAt,
            lastStatusChangeReason,
        )
    }
}
