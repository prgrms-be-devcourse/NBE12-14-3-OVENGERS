package com.ovengers.slotkey.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.time.Clock
import java.time.LocalDateTime
import java.util.Optional

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "clockDateTimeProvider")
class JpaAuditingConfig(
    private val clock: Clock,
) {
    @Bean
    fun clockDateTimeProvider(): DateTimeProvider = DateTimeProvider { Optional.of(LocalDateTime.now(clock)) }
}
