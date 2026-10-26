package com.ovengers.slotkey.member.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "회원 정지 또는 복구 요청")
@JvmRecord
data class MemberStatusChangeRequest(
    @field:Schema(
        description = "회원 상태 변경 사유 (1~500자)",
        example = "운영 정책 위반으로 회원 정지",
    )
    @field:NotBlank(message = "사유를 입력해주세요.")
    @field:Size(
        min = 1,
        max = 500,
        message = "사유는 1~500자여야 합니다.",
    )
    val reason: String,
)
