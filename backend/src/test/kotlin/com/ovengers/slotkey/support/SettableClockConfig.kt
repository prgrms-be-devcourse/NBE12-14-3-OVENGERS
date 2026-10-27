package com.ovengers.slotkey.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 실제 Spring 빈(트랜잭션 프록시 포함)을 쓰되 "지금"만 테스트가 정하도록 Clock을 바꿔 끼우는 설정.
 * `@Import(SettableClockConfig::class)`로 쓰고 [SettableClock]을 주입받아 [SettableClock.set]으로 시각을 정한다.
 */
@TestConfiguration
class SettableClockConfig {
    class SettableClock : Clock() {
        @Volatile
        private var instant: Instant = ZonedDateTime.of(2027, 3, 1, 0, 1, 0, 0, KST).toInstant()

        fun set(time: ZonedDateTime) {
            this.instant = time.toInstant()
        }

        override fun getZone(): ZoneId = KST

        override fun withZone(zone: ZoneId): Clock = fixed(instant, zone)

        override fun instant(): Instant = instant
    }

    @Bean
    @Primary
    fun settableClock(): SettableClock = SettableClock()

    companion object {
        @JvmField
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
