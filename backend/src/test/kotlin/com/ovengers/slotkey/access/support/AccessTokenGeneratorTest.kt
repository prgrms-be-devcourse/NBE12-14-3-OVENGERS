package com.ovengers.slotkey.access.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.security.SecureRandom
import java.util.Base64

class AccessTokenGeneratorTest {
    @Test
    @DisplayName("32바이트 난수를 사용해 패딩 없는 URL 안전 토큰을 생성한다")
    fun encodesRandomBytesAsUrlSafeToken() {
        val randomBytes =
            ByteArray(32) { it.toByte() }.also {
                it[0] = 0xfb.toByte()
                it[1] = 0xff.toByte()
            }
        val generator = AccessTokenGenerator()
        val random =
            object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) {
                    randomBytes.copyInto(bytes)
                }
            }
        ReflectionTestUtils.setField(generator, "secureRandom", random)

        val token = generator.generate()
        assertThat(token).isEqualTo("-_8CAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+")
        assertThat(Base64.getUrlDecoder().decode(token)).containsExactly(*randomBytes)
    }
}
