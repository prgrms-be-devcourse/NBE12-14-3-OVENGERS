package com.ovengers.slotkey.reservation.policy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ReservationRefundPolicyTest {
    private val start = LocalDateTime.of(2026, 9, 17, 14, 0)

    @Test
    @DisplayName("시작 1시간 전보다 훨씬 이르면 위약금이 0이다")
    fun penalty_wellBeforeDeadline_isZero() {
        val now = start.minusDays(1)

        assertThat(ReservationRefundPolicy.calculatePenaltyAmount(now, start, 10_000)).isZero()
    }

    @Test
    @DisplayName("정확히 시작 1시간 전이면 전액 환급이라 위약금이 0이다")
    fun penalty_exactlyOneHourBefore_isZero() {
        val now = start.minusHours(1)

        assertThat(ReservationRefundPolicy.calculatePenaltyAmount(now, start, 10_000)).isZero()
    }

    @Test
    @DisplayName("시작 1시간 전을 1초 넘기면 총액의 50%가 위약금이다")
    fun penalty_oneSecondAfterDeadline_isHalf() {
        val now = start.minusHours(1).plusSeconds(1)

        assertThat(ReservationRefundPolicy.calculatePenaltyAmount(now, start, 10_000)).isEqualTo(5_000)
    }

    @Test
    @DisplayName("시작 정각에 취소하면 총액의 50%가 위약금이다")
    fun penalty_atStartTime_isHalf() {
        assertThat(ReservationRefundPolicy.calculatePenaltyAmount(start, start, 10_000)).isEqualTo(5_000)
    }

    @Test
    @DisplayName("50% 계산은 정수 나눗셈이라 홀수 금액은 내림된다")
    fun penalty_oddAmount_truncatesIntegerDivision() {
        assertThat(ReservationRefundPolicy.calculatePenaltyAmount(start, start, 101)).isEqualTo(50)
        assertThat(ReservationRefundPolicy.calculatePenaltyAmount(start, start, 1)).isZero()
    }
}
