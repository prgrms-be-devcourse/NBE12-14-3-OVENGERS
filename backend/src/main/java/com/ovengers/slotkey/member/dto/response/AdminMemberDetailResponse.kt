package com.ovengers.slotkey.member.dto.response

@JvmRecord
data class AdminMemberDetailResponse(
    val member: AdminMemberResponse,
    val totalReservationCount: Long,
    val suspensionCount: Long,
)
