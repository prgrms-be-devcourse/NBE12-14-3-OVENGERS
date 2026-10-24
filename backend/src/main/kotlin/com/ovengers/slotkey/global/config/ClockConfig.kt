package com.ovengers.slotkey.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@Configuration
class ClockConfig {
    companion object {
        @JvmField
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }

    @Bean
    fun clock(): Clock = Clock.system(DEFAULT_ZONE)
}
