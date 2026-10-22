package com.ovengers.slotkey.auth.mail

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("local", "prod")
class SmtpMailCredentialsValidator(
    @Value("\${spring.mail.username:}") username: String,
    @Value("\${spring.mail.password:}") password: String
) {
    init {
        check(!(username.isBlank() || password.isBlank())) { "SPRING_MAIL_USERNAME and SPRING_MAIL_PASSWORD must be configured" }
    }
}
