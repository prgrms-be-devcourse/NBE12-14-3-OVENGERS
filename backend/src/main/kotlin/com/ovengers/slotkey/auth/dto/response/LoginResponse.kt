package com.ovengers.slotkey.auth.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "로그인 및 액세스 토큰 재발급 응답")
@JvmRecord
data class LoginResponse(
    @field:Schema(
        description = """
            API 인증에 사용하는 JWT 액세스 토큰.
            요청 시 Authorization: Bearer {accessToken} 헤더로 전달합니다.
            리프레시 토큰은 이 응답 본문에 포함되지 않습니다.
        """,
    )
    val accessToken: String,
)
