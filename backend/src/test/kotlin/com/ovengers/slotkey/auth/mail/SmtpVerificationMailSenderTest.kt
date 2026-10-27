@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.auth.mail

import com.ovengers.slotkey.auth.entity.VerificationPurpose
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

internal class SmtpVerificationMailSenderTest {
    private val mailSender: JavaMailSender = mock()

    @Test fun 회원가입_메일의_발신자_수신자_제목과_본문을_구성한다() {
        val sender: SmtpVerificationMailSender =
            SmtpVerificationMailSender(
                mailSender,
                "slotkey@example.com",
                "slotkey@example.com",
            )

        sender.sendCode(
            "member@example.com",
            VerificationPurpose.SIGNUP,
            "123456",
        )

        val message: SimpleMailMessage = capturedMessage()
        assertThat(message.from).isEqualTo("slotkey@example.com")
        assertThat(message.to).containsExactly("member@example.com")
        assertThat(message.subject).isEqualTo("[SlotKey] 회원가입 인증번호")
        assertThat(message.text)
            .contains("회원가입 인증번호입니다.")
            .contains("인증번호: 123456")
            .contains("유효 시간: 발급 후 3분")
    }

    @Test fun 비밀번호_재설정_메일의_제목과_본문을_구성한다() {
        val sender: SmtpVerificationMailSender =
            SmtpVerificationMailSender(
                mailSender,
                "slotkey@example.com",
                "slotkey@example.com",
            )

        sender.sendCode(
            "member@example.com",
            VerificationPurpose.PASSWORD_RESET,
            "654321",
        )

        val message: SimpleMailMessage = capturedMessage()
        assertThat(message.subject)
            .isEqualTo("[SlotKey] 비밀번호 재설정 인증번호")
        assertThat(message.text)
            .contains("비밀번호 재설정 인증번호입니다.")
            .contains("인증번호: 654321")
    }

    @Test fun 발신주소와_SMTP_로그인주소가_다르면_설정오류로_실패한다() {
        assertThatThrownBy {
            SmtpVerificationMailSender(
                mailSender,
                "different@example.com",
                "slotkey@example.com",
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("VERIFICATION_MAIL_FROM must match SPRING_MAIL_USERNAME")
    }

    private fun capturedMessage(): SimpleMailMessage {
        val captor = argumentCaptor<SimpleMailMessage>()
        verify(mailSender).send(captor.capture())
        return captor.lastValue
    }
}
