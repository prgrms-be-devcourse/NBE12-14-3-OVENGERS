package com.ovengers.slotkey.reservation.scheduler

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

private val log = LoggerFactory.getLogger(ReservationCompletionScheduler::class.java)

/** 시간 경과에 따른 예약 상태 전이 후보를 찾아 건별 트랜잭션 프로세서에 위임한다. */
@Component
class ReservationCompletionScheduler(
    private val reservationRepository: ReservationRepository,
    private val processor: ReservationBatchProcessor,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${slotkey.scheduler.reservation.fixed-delay-ms:60000}")
    fun expireHolds() {
        val now = LocalDateTime.now(clock)
        val ids = reservationRepository.findExpiredHoldIds(now, ReservationStatus.HELD)
        processEach("HOLD_EXPIRE", ids) { id -> processor.expireHold(id, now) }
    }

    @Scheduled(fixedDelayString = "\${slotkey.scheduler.reservation.fixed-delay-ms:60000}")
    fun markNoShows() {
        val now = LocalDateTime.now(clock)
        val ids =
            reservationRepository.findNoShowCandidateIds(
                now.minus(ReservationBatchProcessor.CHECK_IN_WINDOW),
                ReservationStatus.CONFIRMED,
            )
        processEach("NO_SHOW", ids) { id -> processor.markNoShow(id, now) }
    }

    @Scheduled(fixedDelayString = "\${slotkey.scheduler.reservation.fixed-delay-ms:60000}")
    fun autoCheckOut() {
        val now = LocalDateTime.now(clock)
        val ids = reservationRepository.findAutoCheckOutCandidateIds(now, ReservationStatus.IN_USE)
        processEach("AUTO_CHECK_OUT", ids) { id -> processor.autoCheckOut(id, now) }
    }

    private fun processEach(
        jobName: String,
        reservationIds: List<Long>,
        transition: (Long) -> Boolean,
    ) {
        if (reservationIds.isEmpty()) {
            return
        }
        var transitioned = 0
        reservationIds.forEach { reservationId ->
            try {
                transition(reservationId)
                transitioned++
            } catch (exception: RuntimeException) {
                log.error("[{}] reservationId={} 처리 실패", jobName, reservationId, exception)
            }
        }
        log.info("[{}] 대상 {}건 중 {}건 전이", jobName, reservationIds.size, transitioned)
    }
}
