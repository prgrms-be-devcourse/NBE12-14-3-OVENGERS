package com.ovengers.slotkey.support

import com.ovengers.slotkey.global.config.ClockConfig
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime

@TestConfiguration(proxyBeanMethods = false)
class FixedClockConfig {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(FIXED_INSTANT, ClockConfig.DEFAULT_ZONE)

    companion object {
        @JvmField
        val FIXED_DATE_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 17, 12, 0, 0)

        @JvmField
        val FIXED_INSTANT: Instant = FIXED_DATE_TIME.atZone(ClockConfig.DEFAULT_ZONE).toInstant()
    }
}
