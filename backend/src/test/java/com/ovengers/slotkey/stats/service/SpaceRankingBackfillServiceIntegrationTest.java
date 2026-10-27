package com.ovengers.slotkey.stats.service;

import com.ovengers.slotkey.stats.entity.RankingPeriodType;
import com.ovengers.slotkey.stats.entity.SpaceRankingSnapshot;
import com.ovengers.slotkey.stats.repository.SpaceRankingSnapshotRepository;
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport;
import com.ovengers.slotkey.support.SettableClockConfig;
import com.ovengers.slotkey.support.SettableClockConfig.SettableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시작 시 빠진 기간 보충(단계 4). 기준 시각은 2026-10-16(금) 00:01 KST, 보충 범위는 최근 31일(2026-09-15부터)이다.
 */
@Import(SettableClockConfig.class)
class SpaceRankingBackfillServiceIntegrationTest extends ReservationIntegrationTestSupport {

    @Autowired
    private SpaceRankingBackfillService backfillService;
    @Autowired
    private SpaceRankingSnapshotRepository snapshotRepository;
    @Autowired
    private SettableClock settableClock;

    private Long memberId;
    private Long spaceId;

    @BeforeEach
    void setUp() {
        settableClock.set(ZonedDateTime.of(2026, 10, 16, 0, 1, 0, 0, SettableClockConfig.KST));
        memberId = createMember(1_000_000);
        spaceId = createSpace();
        jdbcTemplate.update("UPDATE spaces SET location = '판교' WHERE id = ?", spaceId);
    }

    @AfterEach
    void cleanSnapshots() {
        jdbcTemplate.update("DELETE FROM space_ranking_snapshot");
    }

    @Test
    @DisplayName("보충 범위 안에서 빠진 끝난 일간·주간·월간 기간을 채운다")
    void fillsMissingEndedPeriodsInsideWindow() {
        reservationOn(LocalDateTime.of(2026, 10, 10, 10, 0));   // 토. 2026-10-05 주, 10월
        reservationOn(LocalDateTime.of(2026, 9, 20, 10, 0));    // 일. 2026-09-14 주, 9월

        backfillService.backfillMissing();

        assertThat(rows(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 10))).hasSize(1);
        assertThat(rows(RankingPeriodType.DAILY, LocalDate.of(2026, 9, 20))).hasSize(1);
        assertThat(rows(RankingPeriodType.WEEKLY, LocalDate.of(2026, 10, 5))).hasSize(1);
        assertThat(rows(RankingPeriodType.WEEKLY, LocalDate.of(2026, 9, 14))).hasSize(1);
        assertThat(rows(RankingPeriodType.MONTHLY, LocalDate.of(2026, 9, 1))).hasSize(1);
    }

    @Test
    @DisplayName("진행 중인 오늘·이번 주·이번 달은 계산하지 않는다")
    void doesNotComputeInProgressPeriods() {
        reservationOn(LocalDateTime.of(2026, 10, 16, 10, 0));   // 오늘
        reservationOn(LocalDateTime.of(2026, 10, 13, 10, 0));   // 이번 주 화요일, 이번 달

        backfillService.backfillMissing();

        assertThat(rows(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 16))).isEmpty();
        assertThat(rows(RankingPeriodType.WEEKLY, LocalDate.of(2026, 10, 12))).isEmpty();
        assertThat(rows(RankingPeriodType.MONTHLY, LocalDate.of(2026, 10, 1))).isEmpty();
        assertThat(rows(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 13))).hasSize(1);
    }

    @Test
    @DisplayName("00:01 전(00:00:30)에는 방금 끝난 어제를 계산하지 않아 00:01 완충이 우회되지 않는다")
    void doesNotConfirmJustEndedPeriodBeforeOneMinute() {
        reservationOn(LocalDateTime.of(2026, 10, 15, 10, 0));   // 어제
        reservationOn(LocalDateTime.of(2026, 10, 14, 10, 0));   // 그제
        settableClock.set(ZonedDateTime.of(2026, 10, 16, 0, 0, 30, 0, SettableClockConfig.KST));

        backfillService.backfillMissing();

        assertThat(rows(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 15))).isEmpty();
        assertThat(rows(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 14))).hasSize(1);
    }

    @Test
    @DisplayName("보충 범위(31일) 밖의 일간 기간은 채우지 않는다")
    void doesNotFillDailyPeriodsOlderThanWindow() {
        reservationOn(LocalDateTime.of(2026, 8, 1, 10, 0));

        backfillService.backfillMissing();

        assertThat(rows(RankingPeriodType.DAILY, LocalDate.of(2026, 8, 1))).isEmpty();
        assertThat(rows(RankingPeriodType.MONTHLY, LocalDate.of(2026, 8, 1))).isEmpty();
    }

    @Test
    @DisplayName("이미 확정 행이 있는 기간은 원본이 바뀌었어도 다시 계산하지 않는다")
    void keepsAlreadyConfirmedPeriodsUntouched() {
        reservationOn(LocalDateTime.of(2026, 10, 10, 10, 0));
        snapshotRepository.saveAndFlush(new SpaceRankingSnapshot(RankingPeriodType.DAILY,
                LocalDate.of(2026, 10, 10), spaceId, "판교", 999L, 7, 7, LocalDateTime.of(2026, 10, 11, 0, 1)));

        backfillService.backfillMissing();

        List<SpaceRankingSnapshot> rows = rows(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 10));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getNetRevenue()).isEqualTo(999L);
        assertThat(rows.get(0).getReservationCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("보충을 두 번 실행해도 행이 중복되지 않는다(멱등)")
    void backfillTwiceIsIdempotent() {
        reservationOn(LocalDateTime.of(2026, 10, 10, 10, 0));

        backfillService.backfillMissing();
        long afterFirst = snapshotRepository.count();
        backfillService.backfillMissing();

        assertThat(snapshotRepository.count()).isEqualTo(afterFirst);
        assertThat(afterFirst).isEqualTo(2);   // 일간 10-10 + 주간 10-05. 10월 월간은 진행 중, 9월은 거래 없음
    }

    private List<SpaceRankingSnapshot> rows(RankingPeriodType type, LocalDate periodStart) {
        return snapshotRepository.findAll().stream()
                .filter(row -> row.getPeriodType() == type
                        && row.getPeriodStart().equals(periodStart)
                        && row.getSpaceId().equals(spaceId))
                .toList();
    }

    private void reservationOn(LocalDateTime start) {
        jdbcTemplate.update("""
                INSERT INTO reservation (member_id, space_id, start_time, end_time, status,
                    price_per_slot_snapshot, total_amount, created_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 5000, 5000, ?)
                """, memberId, spaceId, Timestamp.valueOf(start), Timestamp.valueOf(start.plusMinutes(30)),
                Timestamp.valueOf(start.minusDays(1)));
    }
}
