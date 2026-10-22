package com.ovengers.slotkey.access.support

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

@Component
class AccessTokenGenerator {
    private val secureRandom = SecureRandom()

    // 새로운 원문 출입 토큰 생성
    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTE_LENGTH)
        secureRandom.nextBytes(bytes)

        // URL에서 사용할 수 있는 문자열로 변환
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        private const val TOKEN_BYTE_LENGTH = 32
    }
}
