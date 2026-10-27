@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.auth.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

internal class VerificationSecretHasherTest {
    private val hasher: VerificationSecretHasher =
        VerificationSecretHasher(
            Base64.getEncoder().encodeToString(ByteArray(32)),
        )

    @Test fun 정확한_번호만_일치한다() {
        val hash: String = hasher.hashCode(1L, "01234567")

        assertThat(hash).hasSize(64)
        assertThat(hasher.matchesCode(1L, "01234567", hash)).isTrue()
        assertThat(hasher.matchesCode(1L, "01234568", hash)).isFalse()
    }

    @Test fun 다른_Guard에서는_같은_번호도_일치하지_않는다() {
        val hash: String = hasher.hashCode(1L, "01234567")

        assertThat(hasher.matchesCode(2L, "01234567", hash)).isFalse()
    }

    @Test fun 번호와_증표는_같은_원문이라도_검증값이_다르다() {
        val raw = "01234567"

        assertThat(hasher.hashGrant(raw))
            .isNotEqualTo(hasher.hashCode(1L, raw))
    }

    @Test fun 다른_비밀키로는_번호가_일치하지_않는다() {
        val anotherKey = ByteArray(32)
        anotherKey[0] = 1

        val anotherHasher: VerificationSecretHasher =
            VerificationSecretHasher(
                Base64.getEncoder().encodeToString(anotherKey),
            )

        val hash: String = hasher.hashCode(1L, "01234567")

        assertThat(
            anotherHasher.matchesCode(
                1L,
                "01234567",
                hash,
            ),
        ).isFalse()
    }

    @Test fun 짧은_비밀키는_거부한다() {
        val shortKey: String =
            Base64.getEncoder().encodeToString(ByteArray(16))

        assertThatThrownBy { VerificationSecretHasher(shortKey) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
