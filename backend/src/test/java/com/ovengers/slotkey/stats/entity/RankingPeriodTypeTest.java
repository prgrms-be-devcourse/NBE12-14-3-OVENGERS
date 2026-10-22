package com.ovengers.slotkey.stats.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RankingPeriodTypeTest {

    @Test
    @DisplayName("일간 기간은 그 날 하루이고 끝(exclusive)은 다음 날이다")
    void daily() {
        LocalDate date = LocalDate.of(2026, 10, 15);

        assertThat(RankingPeriodType.DAILY.startOf(date)).isEqualTo(date);
        assertThat(RankingPeriodType.DAILY.endOf(date)).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(RankingPeriodType.DAILY.endOf(LocalDate.of(2028, 2, 28)))
                .isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(RankingPeriodType.DAILY.endOf(LocalDate.of(2028, 2, 29)))
                .isEqualTo(LocalDate.of(2028, 3, 1));
    }

    @Test
    @DisplayName("주간 기간은 월요일에 시작하고 수요일·일요일·월요일 모두 같은 주로 묶인다")
    void weeklyStartsOnMonday() {
        LocalDate monday = LocalDate.of(2026, 10, 12);

        assertThat(RankingPeriodType.WEEKLY.startOf(LocalDate.of(2026, 10, 12))).isEqualTo(monday);
        assertThat(RankingPeriodType.WEEKLY.startOf(LocalDate.of(2026, 10, 14))).isEqualTo(monday);
        assertThat(RankingPeriodType.WEEKLY.startOf(LocalDate.of(2026, 10, 18))).isEqualTo(monday);
        assertThat(RankingPeriodType.WEEKLY.startOf(LocalDate.of(2026, 10, 19))).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(RankingPeriodType.WEEKLY.endOf(LocalDate.of(2026, 10, 18))).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(RankingPeriodType.WEEKLY.endOf(LocalDate.of(2026, 10, 19))).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    @DisplayName("연말·연초에 걸친 주는 연도가 바뀌어도 한 주로 묶인다")
    void weeklyAcrossYearBoundary() {
        assertThat(RankingPeriodType.WEEKLY.startOf(LocalDate.of(2027, 1, 1))).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(RankingPeriodType.WEEKLY.startOf(LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(RankingPeriodType.WEEKLY.endOf(LocalDate.of(2027, 1, 1))).isEqualTo(LocalDate.of(2027, 1, 4));
    }

    @Test
    @DisplayName("월간 기간은 1일에 시작하고 말일·윤달·연말 경계에서 다음 달 1일에 끝난다")
    void monthly() {
        assertThat(RankingPeriodType.MONTHLY.startOf(LocalDate.of(2026, 10, 31))).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(RankingPeriodType.MONTHLY.endOf(LocalDate.of(2026, 10, 31))).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(RankingPeriodType.MONTHLY.endOf(LocalDate.of(2028, 2, 29))).isEqualTo(LocalDate.of(2028, 3, 1));
        assertThat(RankingPeriodType.MONTHLY.endOf(LocalDate.of(2027, 2, 28))).isEqualTo(LocalDate.of(2027, 3, 1));
        assertThat(RankingPeriodType.MONTHLY.endOf(LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2027, 1, 1));
    }
}
