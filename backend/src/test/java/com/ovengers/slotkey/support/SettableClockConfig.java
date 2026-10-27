package com.ovengers.slotkey.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 실제 Spring 빈(트랜잭션 프록시 포함)을 쓰되 "지금"만 테스트가 정하도록 Clock을 바꿔 끼우는 설정.
 * {@code @Import(SettableClockConfig.class)}로 쓰고 {@code SettableClock}을 주입받아 {@link SettableClock#set}으로 시각을 정한다.
 */
@TestConfiguration
public class SettableClockConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static class SettableClock extends Clock {
        private volatile Instant instant = ZonedDateTime.of(2027, 3, 1, 0, 1, 0, 0, KST).toInstant();

        public void set(ZonedDateTime time) {
            this.instant = time.toInstant();
        }

        @Override
        public ZoneId getZone() {
            return KST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Bean
    @Primary
    SettableClock settableClock() {
        return new SettableClock();
    }
}
