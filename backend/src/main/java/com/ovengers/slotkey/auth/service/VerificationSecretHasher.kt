package com.ovengers.slotkey.auth.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class VerificationSecretHasher(
    @Value("\${app.verification.hash-secret}") secret: String,
) {
    private val key: SecretKeySpec =
        Base64.getDecoder().decode(secret).let { keyBytes ->
            require(keyBytes.size >= 32) {
                "인증 검증용 비밀키는 최소 32바이트여야 합니다."
            }

            SecretKeySpec(keyBytes, "HmacSHA256")
        }

    fun hashCode(
        guardId: Long,
        rawCode: String,
    ): String = hash("CODE:$guardId:$rawCode")

    fun matchesCode(
        guardId: Long,
        rawCode: String,
        storedHash: String,
    ): Boolean {
        val expected = HexFormat.of().parseHex(storedHash)
        val actual =
            HexFormat.of().parseHex(
                hashCode(guardId, rawCode),
            )

        return MessageDigest.isEqual(expected, actual)
    }

    fun hashGrant(rawGrant: String): String = hash("GRANT:$rawGrant")

    private fun hash(value: String): String {
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)

            val result =
                mac.doFinal(
                    value.toByteArray(StandardCharsets.UTF_8),
                )

            return HexFormat.of().formatHex(result)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException(
                "인증 검증값 생성에 실패했습니다.",
                e,
            )
        }
    }
}
