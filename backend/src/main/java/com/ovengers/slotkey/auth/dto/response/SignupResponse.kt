package com.ovengers.slotkey.auth.dto.response

import com.ovengers.slotkey.member.entity.Member

@JvmRecord
data class SignupResponse(
    val memberId: Long,
    val email: String,
    val nickname: String,
) {
    // 회원 엔티티에서 응답에 필요한 정보만 추출
    constructor(member: Member) : this(
        member.id,
        member.email,
        member.nickname,
    )
}
