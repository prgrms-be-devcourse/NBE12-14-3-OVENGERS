package com.ovengers.slotkey.stats.service

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.support.SettableClockConfig
import com.ovengers.slotkey.support.SettableClockConfig.SettableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/** 오늘 상태별 건수(단계 6). 실시간 집계이고 스냅샷이 아니다. 기준 시각은 2026-10-16 00:30 KST. */
@Import(SettableClockConfig::class)
class ReservationStatusCountServiceIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var service: ReservationStatusCountService

    @Autowired
    private lateinit var settableClock: SettableClock

    private var memberId: Long = 0L
    private var spaceId: Long = 0L

    @BeforeEach
    fun setUp() {
        settableClock.set(ZonedDateTime.of(2026, 10, 16, 0, 30, 0, 0, SettableClockConfig.KST))
        memberId = createMember(0)
        spaceId = createSpace()
    }

    @Test
    @DisplayName("예약이 없는 날도 일곱 상태를 모두 0으로 채워 내려준다")
    fun allSevenStatusesZeroFilled() {
        val response = service.count(DAY, null)

        assertThat(response.date).isEqualTo(DAY)
        assertThat(response.counts).containsOnlyKeys(*ReservationStatus.entries.toTypedArray())
        assertThat(response.counts.values).containsOnly(0L)
    }

    @Test
    @DisplayName("해당 날짜에 start_time이 속한 예약을 상태별로 센다")
    fun countsByStatus() {
        insert(ReservationStatus.HELD, at(10, 0))
        insert(ReservationStatus.HELD, at(11, 0))
        insert(ReservationStatus.CONFIRMED, at(12, 0))
        insert(ReservationStatus.IN_USE, at(13, 0))
        insert(ReservationStatus.COMPLETED, at(14, 0))
        insert(ReservationStatus.CANCELLED, at(15, 0))
        insert(ReservationStatus.NO_SHOW, at(16, 0))
        insert(ReservationStatus.EXPIRED, at(17, 0))

        val response = service.count(DAY, null)

        assertThat(response.counts[ReservationStatus.HELD]).isEqualTo(2L)
        assertThat(response.counts[ReservationStatus.CONFIRMED]).isEqualTo(1L)
        assertThat(response.counts[ReservationStatus.IN_USE]).isEqualTo(1L)
        assertThat(response.counts[ReservationStatus.COMPLETED]).isEqualTo(1L)
        assertThat(response.counts[ReservationStatus.CANCELLED]).isEqualTo(1L)
        assertThat(response.counts[ReservationStatus.NO_SHOW]).isEqualTo(1L)
        assertThat(response.counts[ReservationStatus.EXPIRED]).isEqualTo(1L)
    }

    @Test
    @DisplayName("KST 자정 경계: 전날 23:30 시작은 전날, 00:00 시작은 그날, 다음 날 00:00 시작은 제외된다")
    fun midnightBoundary() {
        insert(ReservationStatus.CONFIRMED, LocalDateTime.of(2026, 10, 15, 23, 30))
        insert(ReservationStatus.CONFIRMED, LocalDateTime.of(2026, 10, 16, 0, 0))
        insert(ReservationStatus.CONFIRMED, LocalDateTime.of(2026, 10, 16, 23, 30))
        insert(ReservationStatus.CONFIRMED, LocalDateTime.of(2026, 10, 17, 0, 0))

        assertThat(service.count(DAY, null).counts[ReservationStatus.CONFIRMED]).isEqualTo(2L)
        assertThat(service.count(DAY.minusDays(1), null).counts[ReservationStatus.CONFIRMED]).isEqualTo(1L)
    }

    @Test
    @DisplayName("spaceId를 주면 그 공간의 예약만 센다")
    fun spaceFilter() {
        val otherSpace = createSpace()
        insert(ReservationStatus.CONFIRMED, at(10, 0))
        insert(otherSpace, ReservationStatus.CONFIRMED, at(11, 0))
        insert(otherSpace, ReservationStatus.CONFIRMED, at(12, 0))

        assertThat(service.count(DAY, spaceId).counts[ReservationStatus.CONFIRMED]).isEqualTo(1L)
        assertThat(service.count(DAY, otherSpace).counts[ReservationStatus.CONFIRMED]).isEqualTo(2L)
    }

    @Test
    @DisplayName("date를 생략하면 주입된 Clock의 오늘(KST)을 쓴다")
    fun defaultsToTodayInKst() {
        insert(ReservationStatus.HELD, at(9, 0))
        // UTC로는 아직 10-15 15:30이지만 KST 오늘은 10-16이다.
        val response = service.count(null, null)

        assertThat(response.date).isEqualTo(DAY)
        assertThat(response.counts[ReservationStatus.HELD]).isEqualTo(1L)
    }

    private fun at(
        hour: Int,
        minute: Int,
    ): LocalDateTime = LocalDateTime.of(DAY, LocalTime.of(hour, minute))

    private fun insert(
        status: ReservationStatus,
        start: LocalDateTime,
    ) {
        insert(spaceId, status, start)
    }

    private fun insert(
        space: Long,
        status: ReservationStatus,
        start: LocalDateTime,
    ) {
        val startTs = Timestamp.valueOf(start)
        jdbcTemplate.update(
            """
            INSERT INTO reservation (member_id, space_id, start_time, end_time, status,
                price_per_slot_snapshot, total_amount, hold_expires_at, checked_in_at,
                checked_out_at, cancelled_at, created_at)
            VALUES (?, ?, ?, ?, ?, 5000, 5000, ?, ?, ?, ?, ?)
            """.trimIndent(),
            memberId,
            space,
            startTs,
            Timestamp.valueOf(start.plusMinutes(30)),
            status.name,
            if (status == ReservationStatus.HELD) Timestamp.valueOf(start.minusHours(1)) else null,
            if (status == ReservationStatus.IN_USE || status == ReservationStatus.COMPLETED) startTs else null,
            if (status == ReservationStatus.COMPLETED) Timestamp.valueOf(start.plusMinutes(30)) else null,
            if (status == ReservationStatus.CANCELLED) Timestamp.valueOf(start.minusHours(3)) else null,
            Timestamp.valueOf(start.minusDays(1)),
        )
    }

    companion object {
        private val DAY: LocalDate = LocalDate.of(2026, 10, 16)
    }
}
