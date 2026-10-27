package com.ovengers.slotkey.global.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.auditing.DateTimeProvider
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAccessor
import java.util.Optional

class JpaAuditingConfigTest {
    @Test
    @DisplayName("Clock 빈에 설정된 시각을 DateTimeProvider가 반환한다")
    fun clockDateTimeProvider_returnsClockTime() {
        val fixedInstant = Instant.parse("2026-09-16T12:00:00Z")
        val zoneId = ZoneId.of("Asia/Seoul")
        val fixedClock = Clock.fixed(fixedInstant, zoneId)

        val jpaAuditingConfig = JpaAuditingConfig(fixedClock)
        val dateTimeProvider: DateTimeProvider = jpaAuditingConfig.clockDateTimeProvider()

        val now: Optional<TemporalAccessor> = dateTimeProvider.now
        assertThat(now).isPresent()
        assertThat(now.get()).isEqualTo(LocalDateTime.now(fixedClock))
    }
}
