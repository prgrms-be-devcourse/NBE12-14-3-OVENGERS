package com.ovengers.slotkey.auth.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "회원가입 요청")
@JvmRecord
data class SignupRequest(
    @field:Schema(description = "가입할 이메일", example = "swagger.user@example.com")
    @field:NotBlank(message = "이메일을 입력해주세요.")
    @field:Email(message = "이메일 형식이 올바르지 않습니다.")
    @field:Size(max = 254, message = "이메일은 254자 이하여야 합니다.")
    val email: String,
    @field:Schema(description = "비밀번호", example = "Sample1234!")
    @field:NotBlank(message = "비밀번호를 입력해주세요.")
    val password: String,
    @field:Schema(description = "비밀번호와 동일한 확인값", example = "Sample1234!")
    @field:NotBlank(message = "비밀번호 확인을 입력해주세요.")
    val passwordConfirm: String,
    @field:Schema(description = "닉네임 (2~50자)", example = "슬롯유저")
    @field:NotBlank(message = "닉네임을 입력해주세요.")
    @field:Size(min = 2, max = 50, message = "닉네임은 2자 이상 50자 이하여야 합니다.")
    val nickname: String,
    @field:Schema(description = "이메일 인증 완료 후 발급받은 가입용 증표")
    @field:NotBlank(message = "이메일 인증 증표를 전달해주세요.")
    @field:Size(max = 128, message = "인증 증표 형식이 올바르지 않습니다.")
    val grant: String,
)
