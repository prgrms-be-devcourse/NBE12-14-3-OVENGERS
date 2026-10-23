package com.ovengers.slotkey.access.policy

import com.ovengers.slotkey.access.entity.AccessDenyReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DoorAccessTimePolicyTest {
    private val policy = DoorAccessTimePolicy()
    private val startAt = LocalDateTime.of(2026, 9, 16, 14, 0)
    private val endAt = LocalDateTime.of(2026, 9, 16, 15, 0)

    @Test
    @DisplayName("예약 종료 전에는 출입 토큰을 발급할 수 있다")
    fun shouldIssueTokenBeforeReservationEnd() {
        val now = endAt.minusNanos(1)

        val result =
            policy.canIssueToken(
                now,
                endAt,
            )

        assertThat(result).isTrue
    }

    @Test
    @DisplayName("예약 종료 시각부터는 출입 토큰을 발급할 수 없다")
    fun shouldNotIssueTokenAtReservationEnd() {
        val now = endAt

        val result =
            policy.canIssueToken(
                now,
                endAt,
            )

        assertThat(result).isFalse
    }

    @Test
    @DisplayName("예약 시작 전에는 최초 체크인을 거절한다")
    fun shouldRejectFirstCheckInBeforeReservationStart() {
        val now = startAt.minusNanos(1)

        val result =
            policy.findDenyReason(
                now,
                startAt,
                endAt,
                null,
            )

        assertThat(result).isEqualTo(AccessDenyReason.OUTSIDE_ALLOWED_TIME)
    }

    @Test
    @DisplayName("최초 체크인은 예약 시작 15분 후까지 가능하다")
    fun shouldAllowFirstCheckInUntilFifteenMinutesAfterStart() {
        val now = startAt.plusMinutes(15)

        val result =
            policy.findDenyReason(
                now,
                startAt,
                endAt,
                null,
            )

        assertThat(result).isNull()
    }

    @Test
    @DisplayName("최초 체크인이 예약 시작 15분 후보다 늦으면 거절한다")
    fun shouldRejectFirstCheckInAfterFifteenMinutes() {
        val now = startAt.plusMinutes(15).plusNanos(1)

        val result =
            policy.findDenyReason(
                now,
                startAt,
                endAt,
                null,
            )

        assertThat(result).isEqualTo(AccessDenyReason.OUTSIDE_ALLOWED_TIME)
    }

    @Test
    @DisplayName("체크인 이후에는 예약 종료 전까지 재입장할 수 있다")
    fun shouldAllowReentryBeforeReservationEnd() {
        val checkedInAt = startAt.plusMinutes(5)

        val now = endAt.minusNanos(1)

        val result =
            policy.findDenyReason(
                now,
                startAt,
                endAt,
                checkedInAt,
            )

        assertThat(result).isNull()
    }

    @Test
    @DisplayName("체크인 시각과 같으면 재입장을 거절한다")
    fun shouldRejectReentryAtCheckInTime() {
        val checkedInAt = startAt.plusMinutes(5)

        val result =
            policy.findDenyReason(
                checkedInAt,
                startAt,
                endAt,
                checkedInAt,
            )

        assertThat(result).isEqualTo(AccessDenyReason.OUTSIDE_ALLOWED_TIME)
    }

    @Test
    @DisplayName("체크인 시각보다 이르면 재입장을 거절한다")
    fun shouldRejectReentryBeforeCheckInTime() {
        val checkedInAt = startAt.plusMinutes(5)

        val now = checkedInAt.minusNanos(1)

        val result =
            policy.findDenyReason(
                now,
                startAt,
                endAt,
                checkedInAt,
            )

        assertThat(result).isEqualTo(AccessDenyReason.OUTSIDE_ALLOWED_TIME)
    }

    @Test
    @DisplayName("예약 종료 시각부터는 재입장을 거절한다")
    fun shouldRejectReentryAtReservationEnd() {
        val checkedInAt = startAt.plusMinutes(5)

        val result =
            policy.findDenyReason(
                endAt,
                startAt,
                endAt,
                checkedInAt,
            )

        assertThat(result).isEqualTo(AccessDenyReason.OUTSIDE_ALLOWED_TIME)
    }
}
