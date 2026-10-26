package com.ovengers.slotkey.member.dto.request

import com.ovengers.slotkey.member.entity.MemberStatus

@JvmRecord
data class AdminMemberSearchCondition(
    val status: MemberStatus?,
    val keyword: String?,
)
