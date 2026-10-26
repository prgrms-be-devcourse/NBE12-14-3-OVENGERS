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
            requireNotNull(member.id),
            member.email,
            member.nickname,
            member.role,
            member.status,
            member.balance,
            requireNotNull(member.createdAt),
            lastStatusChangedAt,
            lastStatusChangeReason,
        )
    }
}
