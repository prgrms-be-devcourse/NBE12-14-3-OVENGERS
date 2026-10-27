package com.ovengers.slotkey.stats.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import com.ovengers.slotkey.stats.entity.RankingPeriodType;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class SpaceRankingSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SpaceRankingConfirmService confirmService = mock(SpaceRankingConfirmService.class);
    private final SpaceRankingBackfillService backfillService = mock(SpaceRankingBackfillService.class);

    private SpaceRankingScheduler schedulerAt(int year, int month, int day) {
        Clock clock = Clock.fixed(ZonedDateTime.of(year, month, day, 0, 1, 0, 0, KST).toInstant(), KST);
        return new SpaceRankingScheduler(confirmService, backfillService, clock);
    }

    @Test
    @DisplayName("평일(월요일·1일 아님) 00:01에는 어제의 일간만 확정한 뒤 빠진 기간을 보충한다")
    void ordinaryDayConfirmsOnlyDaily() {
        schedulerAt(2026, 10, 14).runDaily();   // 수

        InOrder order = inOrder(confirmService, backfillService);
        order.verify(confirmService).confirm(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 13));
        order.verify(backfillService).backfillMissing();
        verify(confirmService, never()).confirm(RankingPeriodType.WEEKLY, LocalDate.of(2026, 10, 13));
        verify(confirmService, never()).confirm(RankingPeriodType.MONTHLY, LocalDate.of(2026, 10, 13));
    }

    @Test
    @DisplayName("월요일 00:01에는 일간과 함께 방금 끝난 주간을 확정한다")
    void mondayConfirmsDailyAndWeekly() {
        schedulerAt(2026, 10, 12).runDaily();   // 월

        verify(confirmService).confirm(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 11));
        verify(confirmService).confirm(RankingPeriodType.WEEKLY, LocalDate.of(2026, 10, 11));
        verify(confirmService, never()).confirm(RankingPeriodType.MONTHLY, LocalDate.of(2026, 10, 11));
    }

    @Test
    @DisplayName("매월 1일 00:01에는 일간과 함께 방금 끝난 월간을 확정한다")
    void firstOfMonthConfirmsDailyAndMonthly() {
        schedulerAt(2026, 10, 1).runDaily();    // 목

        verify(confirmService).confirm(RankingPeriodType.DAILY, LocalDate.of(2026, 9, 30));
        verify(confirmService).confirm(RankingPeriodType.MONTHLY, LocalDate.of(2026, 9, 30));
        verify(confirmService, never()).confirm(RankingPeriodType.WEEKLY, LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("월요일이면서 1일이면 일간·주간·월간을 모두 확정한다")
    void mondayAndFirstConfirmsAll() {
        schedulerAt(2026, 6, 1).runDaily();     // 월, 6월 1일

        verify(confirmService).confirm(RankingPeriodType.DAILY, LocalDate.of(2026, 5, 31));
        verify(confirmService).confirm(RankingPeriodType.WEEKLY, LocalDate.of(2026, 5, 31));
        verify(confirmService).confirm(RankingPeriodType.MONTHLY, LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("한 기간의 확정이 실패해도 나머지 기간과 보충은 계속 실행되고 예외는 밖으로 나가지 않는다")
    void oneFailureDoesNotStopTheRest() {
        doThrow(new IllegalStateException("db down"))
                .when(confirmService).confirm(RankingPeriodType.DAILY, LocalDate.of(2026, 5, 31));

        assertThatCode(() -> schedulerAt(2026, 6, 1).runDaily()).doesNotThrowAnyException();

        verify(confirmService).confirm(RankingPeriodType.WEEKLY, LocalDate.of(2026, 5, 31));
        verify(confirmService).confirm(RankingPeriodType.MONTHLY, LocalDate.of(2026, 5, 31));
        verify(backfillService).backfillMissing();
    }

    @Test
    @DisplayName("서버 시작 시 보충을 실행하고, 실패해도 시작을 막지 않는다")
    void startupRunsBackfillAndSwallowsFailure() {
        doThrow(new IllegalStateException("db down")).when(backfillService).backfillMissing();

        assertThatCode(() -> schedulerAt(2026, 10, 14).onApplicationReady()).doesNotThrowAnyException();

        verify(backfillService).backfillMissing();
        verifyNoMoreInteractions(confirmService);
    }
}
