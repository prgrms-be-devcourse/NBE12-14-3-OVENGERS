package com.ovengers.slotkey.auth.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "비밀번호 재설정 요청")
@JvmRecord
data class PasswordResetRequest(
    @field:Schema(description = "등록된 이메일")
    @field:NotBlank(message = "이메일을 입력해주세요.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    @field:Size(max = 254, message = "이메일은 254자 이하여야 합니다.")
    val email: String,
    @field:Schema(description = "비밀번호 재설정 인증 증표")
    @field:NotBlank(message = "이메일 인증 증표를 전달해주세요.")
    @field:Size(max = 128, message = "인증 증표 형식이 올바르지 않습니다.")
    val grant: String,
    @field:Schema(description = "새 비밀번호")
    @field:NotBlank(message = "새 비밀번호를 입력해주세요.")
    val password: String,
    @field:Schema(description = "새 비밀번호 확인값")
    @field:NotBlank(message = "새 비밀번호 확인을 입력해주세요.")
    val passwordConfirm: String,
)
