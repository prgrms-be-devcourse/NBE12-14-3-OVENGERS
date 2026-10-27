@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.member.entity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

internal class MemberEmailVerificationTest {
    @Test fun 신규_회원은_미인증이다() {
        val member = Member("user@example.com", "회원")

        assertThat(member.isEmailVerified()).isFalse()
        assertThat(member.emailVerifiedAt).isNull()
    }

    @Test fun 인증하면_인증_시각이_기록된다() {
        val member = Member("user@example.com", "회원")
        val verifiedAt: LocalDateTime =
            LocalDateTime.of(2026, 10, 13, 12, 0)

        member.verifyEmail(verifiedAt)

        assertThat(member.isEmailVerified()).isTrue()
        assertThat(member.emailVerifiedAt).isEqualTo(verifiedAt)
    }

    @Test fun 재호출해도_최초_인증_시각을_유지한다() {
        val member = Member("user@example.com", "회원")
        val first: LocalDateTime =
            LocalDateTime.of(2026, 10, 13, 12, 0)

        member.verifyEmail(first)
        member.verifyEmail(first.plusMinutes(5))

        assertThat(member.emailVerifiedAt).isEqualTo(first)
    }

    @Test fun 인증_시각이_없으면_거부하고_미인증을_유지한다() {
        val member = Member("user@example.com", "회원")

        assertThatThrownBy { member.verifyEmail(null) }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThat(member.isEmailVerified()).isFalse()
    }
}
