package com.ovengers.slotkey.global.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 스케줄링 활성화 설정.
 * [com.ovengers.slotkey.reservation.scheduler.ReservationCompletionScheduler]에서
 * @Scheduled 애노테이션이 동작하도록 활성화합니다.
 */
@Configuration
@EnableScheduling
class SchedulerConfig
