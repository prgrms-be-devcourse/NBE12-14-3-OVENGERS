package com.ovengers.slotkey.reservation.scheduler

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.eqNonNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * ReservationCompletionScheduler는 후보 id 조회 -> ReservationBatchProcessor 위임 -> 건별 예외
 * 격리(processEach)까지만 담당한다. 실제 전이 조건(now < startTime 등)의 경계값은
 * ReservationRepository의 조건부 UPDATE 쿼리 자체가 판정하므로, 그 경계 정확성은
 * 통합/리포지토리 테스트에서 검증한다 — 여기서는 스케줄러가 올바른 시각 인자로
 * 위임하고, 한 건의 실패가 나머지 처리를 막지 않는지를 검증한다.
 */
@ExtendWith(MockitoExtension::class)
class ReservationCompletionSchedulerTest {
    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var processor: ReservationBatchProcessor

    private lateinit var clock: Clock
    private lateinit var scheduler: ReservationCompletionScheduler
    private lateinit var now: LocalDateTime

    @BeforeEach
    fun setUp() {
        now = LocalDateTime.of(2026, 9, 17, 15, 0)
        clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE)
        scheduler = ReservationCompletionScheduler(reservationRepository, processor, clock)
    }

    @Test
    @DisplayName("expireHolds는 만료 후보 id 전부에 대해 processor.expireHold를 현재 시각으로 호출한다")
    fun expireHolds_callsProcessorForEachCandidate() {
        given(reservationRepository.findExpiredHoldIds(now, ReservationStatus.HELD))
            .willReturn(listOf(1L, 2L))

        scheduler.expireHolds()

        verify(processor).expireHold(1L, now)
        verify(processor).expireHold(2L, now)
    }

    @Test
    @DisplayName("markNoShows는 체크인 창(15분) 만큼 이전 시각을 기준으로 노쇼 후보를 조회한다")
    fun markNoShows_usesCheckInWindowThreshold() {
        given(reservationRepository.findNoShowCandidateIds(anyNonNull(), eqNonNull(ReservationStatus.CONFIRMED)))
            .willReturn(listOf(10L))

        scheduler.markNoShows()

        verify(reservationRepository).findNoShowCandidateIds(
            now.minus(ReservationBatchProcessor.CHECK_IN_WINDOW),
            ReservationStatus.CONFIRMED,
        )
        verify(processor).markNoShow(10L, now)
    }

    @Test
    @DisplayName("autoCheckOut은 종료 시각이 지난 IN_USE 후보 전부를 처리한다")
    fun autoCheckOut_callsProcessorForEachCandidate() {
        given(reservationRepository.findAutoCheckOutCandidateIds(now, ReservationStatus.IN_USE))
            .willReturn(listOf(20L, 21L))

        scheduler.autoCheckOut()

        verify(processor).autoCheckOut(20L, now)
        verify(processor).autoCheckOut(21L, now)
    }

    @Test
    @DisplayName("후보 목록이 비어 있으면 processor를 호출하지 않는다")
    fun expireHolds_emptyCandidates_doesNotCallProcessor() {
        given(reservationRepository.findExpiredHoldIds(now, ReservationStatus.HELD))
            .willReturn(listOf())

        scheduler.expireHolds()

        verifyNoInteractions(processor)
    }

    @Test
    @DisplayName("한 건 처리 중 예외가 발생해도 나머지 후보는 계속 처리된다")
    fun expireHolds_oneFailure_othersStillProcessed() {
        given(reservationRepository.findExpiredHoldIds(now, ReservationStatus.HELD))
            .willReturn(listOf(1L, 2L, 3L))
        doThrow(RuntimeException("DB 오류")).`when`(processor).expireHold(2L, now)

        scheduler.expireHolds()

        verify(processor).expireHold(1L, now)
        verify(processor).expireHold(2L, now)
        verify(processor).expireHold(3L, now)
    }

    companion object {
        private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
