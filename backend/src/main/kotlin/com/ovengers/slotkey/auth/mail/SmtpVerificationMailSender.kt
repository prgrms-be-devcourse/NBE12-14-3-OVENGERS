package com.ovengers.slotkey.auth.mail

import com.ovengers.slotkey.auth.entity.VerificationPurpose
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

@Component
class SmtpVerificationMailSender(
    private val mailSender: JavaMailSender,
    @Value("\${app.verification.mail-from}") private val from: String,
    @Value("\${spring.mail.username}") smtpUsername: String,
) : VerificationMailSender {
    init {
        if (from != smtpUsername) {
            throw IllegalStateException("VERIFICATION_MAIL_FROM must match SPRING_MAIL_USERNAME")
        }
    }

    override fun sendCode(
        email: String,
        purpose: VerificationPurpose,
        rawCode: String,
    ) {
        val action =
            when (purpose) {
                VerificationPurpose.SIGNUP -> "회원가입"
                VerificationPurpose.PASSWORD_RESET -> "비밀번호 재설정"
            }

        val message =
            SimpleMailMessage().apply {
                setFrom(this@SmtpVerificationMailSender.from)
                setTo(email)
                subject = "[SlotKey] $action 인증번호"
                text =
                    """
                    SlotKey $action 인증번호입니다.

                    인증번호: $rawCode
                    유효 시간: 발급 후 3분

                    본인이 요청하지 않았다면 이 메일을 무시해주세요.
                    """.trimIndent() + "\n"
            }
        mailSender.send(message)
    }
}
