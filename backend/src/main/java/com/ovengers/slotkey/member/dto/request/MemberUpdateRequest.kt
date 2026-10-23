package com.ovengers.slotkey.member.dto.request

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "내 닉네임 수정 요청")
class MemberUpdateRequest
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    constructor(
        @JsonProperty("nickname")
        nickname: String?,
    ) {
        @field:Schema(
            description = "닉네임 (2~50자)",
            example = "새닉네임",
        )
        @field:NotBlank(message = "닉네임을 입력해주세요.")
        @field:Size(
            min = 2,
            max = 50,
            message = "닉네임은 2자 이상 50자 이하여야 합니다.",
        )
        val nickname: String? = nickname?.trim()

        fun nickname(): String? = nickname
    }
