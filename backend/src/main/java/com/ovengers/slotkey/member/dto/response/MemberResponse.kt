package com.ovengers.slotkey.member.dto.response

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "로그인한 회원의 현재 정보")
@JvmRecord
data class MemberResponse(
    @field:Schema(description = "회원 ID", example = "1")
    val memberId: Long,
    @field:Schema(description = "이메일", example = "swagger.user@example.com")
    val email: String,
    @field:Schema(description = "닉네임", example = "슬롯유저")
    val nickname: String,
    @field:Schema(description = "현재 회원 역할", example = "USER")
    val role: MemberRole,
    @field:Schema(description = "현재 보유 크레딧", example = "300000")
    val balance: Int,
    @field:Schema(description = "가입 시각", example = "2026-09-23T09:00:00")
    val createdAt: LocalDateTime,
) {
    constructor(member: Member) : this(
        member.getId(),
        member.getEmail(),
        member.getNickname(),
        member.getRole(),
        member.getBalance(),
        member.getCreatedAt(),
    )
}
