package com.ovengers.slotkey.access.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AccessTokenHasherTest {
    @Test
    @DisplayName("원문 토큰의 UTF-8 SHA-256 결과를 소문자 64자리 16진수로 반환한다")
    fun matchesKnownSha256Vectors() {
        val hasher = AccessTokenHasher()
        assertThat(hasher.hash("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(hasher.hash("출입-token"))
            .isEqualTo("d1f95b408233616fd83e5bd6e8693b3a0c4df2bd7817bf317761dd1c59ac610b")
    }
}
