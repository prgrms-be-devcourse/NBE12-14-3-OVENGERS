package com.ovengers.slotkey.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@TestConfiguration(proxyBeanMethods = false)
class SurveyGoldenClockConfig {
    @Bean
    @Primary
    fun surveyClock(): Clock = Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneId.of("Asia/Seoul"))
}
