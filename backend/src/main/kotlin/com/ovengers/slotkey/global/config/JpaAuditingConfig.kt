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
    /**
     * JPA Auditing이 사용하는 시간 공급자.
     * ClockConfig의 Clock 빈을 통해 시간을 제공하여,
     * 테스트에서 Clock을 고정하면 @CreatedDate, @LastModifiedDate도 함께 고정됩니다.
     */
    @Bean
    fun clockDateTimeProvider(): DateTimeProvider = DateTimeProvider { Optional.of(LocalDateTime.now(clock)) }
}
