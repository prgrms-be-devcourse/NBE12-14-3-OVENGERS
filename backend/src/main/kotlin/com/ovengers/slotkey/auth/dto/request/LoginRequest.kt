package com.ovengers.slotkey.auth.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "로그인 요청")
@JvmRecord
data class LoginRequest(
    @field:Schema(description = "가입한 이메일", example = "swagger.user@example.com")
    @field:NotBlank(message = "이메일을 입력해주세요")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    @field:Size(max = 254, message = "이메일은 254자 이하여야 합니다.")
    val email: String,
    @field:Schema(description = "비밀번호", example = "Sample1234!")
    @field:NotBlank(message = "비밀번호를 입력해주세요.")
    val password: String,
)
