package com.ovengers.slotkey.stats.service

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.SpaceRankingSnapshot
import com.ovengers.slotkey.stats.repository.SpaceRankingSnapshotRepository
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.support.SettableClockConfig
import com.ovengers.slotkey.support.SettableClockConfig.SettableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 베스트 공간 랭킹 확정 계산(단계 3)의 경계 테스트. 시간은 고정 Clock(2027-03-01 00:01 KST)으로 주입하고,
 * 모든 테스트 기간은 그 이전의 "끝난 기간"이다.
 */
@Import(SettableClockConfig::class)
class SpaceRankingConfirmServiceIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var snapshotRepository: SpaceRankingSnapshotRepository

    @Autowired
    private lateinit var service: SpaceRankingConfirmService

    @Autowired
    private lateinit var settableClock: SettableClock

    private var memberId = 0L
    private var spaceId = 0L

    @BeforeEach
    fun setUp() {
        settableClock.set(ZonedDateTime.of(2027, 3, 1, 0, 1, 0, 0, KST))
        memberId = createMember(1_000_000)
        spaceId = createSpace()
        jdbcTemplate.update("UPDATE spaces SET location = '판교' WHERE id = ?", spaceId)
    }

    @AfterEach
    fun cleanSnapshots() {
        jdbcTemplate.update("DELETE FROM space_ranking_snapshot")
    }

    // ---------- 순매출: 결제·환불·위약금 ----------

    @Test
    @DisplayName("결제만 있는 확정 예약은 결제액이 순매출이고 예약 1건·슬롯 수가 센다")
    fun confirmedReservationCountsRevenueCountAndSlots() {
        val start = at(2026, 3, 10, 10, 0)
        val reservation = insertReservation(ReservationStatus.CONFIRMED, start, start.plusMinutes(90))
        charge(reservation, 3 * PRICE, start.minusHours(2))

        val row = confirmDaily(LocalDate.of(2026, 3, 10))!!

        assertThat(row.netRevenue).isEqualTo(3L * PRICE)
        assertThat(row.reservationCount).isEqualTo(1)
        assertThat(row.slotCount).isEqualTo(3)
    }

    @Test
    @DisplayName("결제일의 매출과 이용일의 건수는 서로 다른 날짜에 잡힌다")
    fun revenueFollowsPaymentDayAndCountFollowsStartDay() {
        val start = at(2026, 3, 12, 10, 0)
        val reservation = insertReservation(ReservationStatus.CONFIRMED, start, start.plusMinutes(60))
        charge(reservation, 2 * PRICE, at(2026, 3, 10, 9, 0))

        val paymentDay = confirmDaily(LocalDate.of(2026, 3, 10))!!
        val useDay = confirmDaily(LocalDate.of(2026, 3, 12))!!

        assertThat(paymentDay.netRevenue).isEqualTo(2L * PRICE)
        assertThat(paymentDay.reservationCount).isZero()
        assertThat(useDay.netRevenue).isZero()
        assertThat(useDay.reservationCount).isEqualTo(1)
    }

    @Test
    @DisplayName("위약금 50% 취소는 건수에서 빠지지만 위약금이 순매출로 남는다")
    fun penaltyCancellationKeepsOnlyPenaltyAsRevenue() {
        val start = at(2026, 3, 10, 10, 0)
        val reservation = insertReservation(ReservationStatus.CANCELLED, start, start.plusMinutes(60))
        val day = at(2026, 3, 10, 8, 0)
        charge(reservation, 2 * PRICE, day.minusMinutes(30))
        ledger(reservation, "REFUND", 2 * PRICE, day)
        ledger(reservation, "PENALTY", -PRICE, day)

        val row = confirmDaily(LocalDate.of(2026, 3, 10))!!

        assertThat(row.netRevenue).isEqualTo(PRICE.toLong())
        assertThat(row.reservationCount).isZero()
        assertThat(row.slotCount).isZero()
    }

    @Test
    @DisplayName("전액 환불 취소(사용자 1시간 전 취소·관리자 강제취소 동일 원장)는 순매출 0이라 행이 저장되지 않는다")
    fun fullRefundCancellationLeavesNoRow() {
        val start = at(2026, 3, 10, 10, 0)
        val reservation = insertReservation(ReservationStatus.CANCELLED, start, start.plusMinutes(60))
        val day = at(2026, 3, 10, 8, 0)
        charge(reservation, 2 * PRICE, day.minusMinutes(30))
        ledger(reservation, "REFUND", 2 * PRICE, day)

        assertThat(confirmDaily(LocalDate.of(2026, 3, 10))).isNull()
        assertThat(snapshotsOf(RankingPeriodType.DAILY, LocalDate.of(2026, 3, 10))).isEmpty()
    }

    @Test
    @DisplayName("NO_SHOW는 환불이 없으므로 결제액이 그대로 순매출이고 건수에 포함된다")
    fun noShowKeepsRevenueAndCount() {
        val start = at(2026, 3, 10, 10, 0)
        val reservation = insertReservation(ReservationStatus.NO_SHOW, start, start.plusMinutes(60))
        charge(reservation, 2 * PRICE, start.minusHours(5))

        val row = confirmDaily(LocalDate.of(2026, 3, 10))!!

        assertThat(row.netRevenue).isEqualTo(2L * PRICE)
        assertThat(row.reservationCount).isEqualTo(1)
    }

    @Test
    @DisplayName("HELD·EXPIRED·CANCELLED 예약은 건수·슬롯에 들어가지 않고 IN_USE·COMPLETED는 들어간다")
    fun onlyCountableStatusesAreCounted() {
        val start = at(2026, 3, 10, 10, 0)
        insertReservation(ReservationStatus.HELD, start, start.plusMinutes(30))
        insertReservation(ReservationStatus.EXPIRED, start.plusHours(1), start.plusHours(1).plusMinutes(30))
        insertReservation(ReservationStatus.CANCELLED, start.plusHours(2), start.plusHours(2).plusMinutes(30))
        insertReservation(ReservationStatus.IN_USE, start.plusHours(3), start.plusHours(3).plusMinutes(60))
        insertReservation(ReservationStatus.COMPLETED, start.plusHours(5), start.plusHours(5).plusMinutes(30))

        val row = confirmDaily(LocalDate.of(2026, 3, 10))!!

        assertThat(row.reservationCount).isEqualTo(2)
        assertThat(row.slotCount).isEqualTo(3)
        assertThat(row.netRevenue).isZero()
    }

    // ---------- 현금주의 ----------

    @Test
    @DisplayName("확정 후 생긴 환불은 지난 확정값을 바꾸지 않고 환불일의 값에 음수로 반영된다")
    fun laterRefundDoesNotChangeConfirmedDayAndLandsOnRefundDay() {
        val start = at(2026, 3, 20, 10, 0)
        val reservation = insertReservation(ReservationStatus.CANCELLED, start, start.plusMinutes(60))
        charge(reservation, 2 * PRICE, at(2026, 3, 10, 9, 0))
        val confirmedBefore = confirmDaily(LocalDate.of(2026, 3, 10))!!
        assertThat(confirmedBefore.netRevenue).isEqualTo(2L * PRICE)

        ledger(reservation, "REFUND", 2 * PRICE, at(2026, 3, 11, 15, 0))

        val chargeDayAgain = confirmDaily(LocalDate.of(2026, 3, 10))!!
        val refundDay = confirmDaily(LocalDate.of(2026, 3, 11))!!
        assertThat(chargeDayAgain.netRevenue).isEqualTo(2L * PRICE)
        assertThat(refundDay.netRevenue).isEqualTo(-2L * PRICE)
    }

    // ---------- 경계 ----------

    @Test
    @DisplayName("KST 자정 경계: 23:59:59는 전날, 00:00:00은 다음 날의 매출·건수로 들어간다")
    fun midnightBoundaryForRevenueAndStartTime() {
        val lastSecond = LocalDateTime.of(2026, 3, 9, 23, 59, 59)
        val midnight = LocalDateTime.of(2026, 3, 10, 0, 0, 0)
        val beforeMidnight = insertReservation(ReservationStatus.CONFIRMED, at(2026, 3, 9, 23, 30), at(2026, 3, 10, 0, 0))
        val afterMidnight = insertReservation(ReservationStatus.CONFIRMED, at(2026, 3, 10, 0, 0), at(2026, 3, 10, 0, 30))
        charge(beforeMidnight, PRICE, lastSecond)
        charge(afterMidnight, PRICE, midnight)

        val before = confirmDaily(LocalDate.of(2026, 3, 9))!!
        val after = confirmDaily(LocalDate.of(2026, 3, 10))!!

        assertThat(before.netRevenue).isEqualTo(PRICE.toLong())
        assertThat(before.reservationCount).isEqualTo(1)
        assertThat(after.netRevenue).isEqualTo(PRICE.toLong())
        assertThat(after.reservationCount).isEqualTo(1)
    }

    @Test
    @DisplayName("자정을 가로지르는 예약은 이용 시작 시각의 날짜에 전체 슬롯이 들어간다")
    fun reservationCrossingMidnightBelongsToStartDay() {
        insertReservation(ReservationStatus.CONFIRMED, at(2026, 3, 9, 23, 30), at(2026, 3, 10, 1, 0))

        val startDay = confirmDaily(LocalDate.of(2026, 3, 9))!!

        assertThat(startDay.reservationCount).isEqualTo(1)
        assertThat(startDay.slotCount).isEqualTo(3)
        assertThat(confirmDaily(LocalDate.of(2026, 3, 10))).isNull()
    }

    @Test
    @DisplayName("주간: 일요일 23:30 시작은 그 주, 월요일 00:00 시작은 다음 주에 들어간다")
    fun weeklyMondayBoundary() {
        insertReservation(ReservationStatus.CONFIRMED, at(2026, 10, 18, 23, 30), at(2026, 10, 19, 0, 30))
        insertReservation(ReservationStatus.CONFIRMED, at(2026, 10, 19, 0, 0), at(2026, 10, 19, 0, 30))

        val week = confirm(RankingPeriodType.WEEKLY, LocalDate.of(2026, 10, 14))!!
        val nextWeek = confirm(RankingPeriodType.WEEKLY, LocalDate.of(2026, 10, 20))!!

        assertThat(week.periodStart).isEqualTo(LocalDate.of(2026, 10, 12))
        assertThat(week.reservationCount).isEqualTo(1)
        assertThat(week.slotCount).isEqualTo(2)
        assertThat(nextWeek.periodStart).isEqualTo(LocalDate.of(2026, 10, 19))
        assertThat(nextWeek.reservationCount).isEqualTo(1)
    }

    @Test
    @DisplayName("월간·연도 경계: 12월 31일 23:30 시작 예약은 12월에 들어가고 1월에는 들어가지 않는다")
    fun monthlyAndYearBoundary() {
        insertReservation(ReservationStatus.CONFIRMED, at(2026, 12, 31, 23, 30), at(2027, 1, 1, 0, 30))

        val december = confirm(RankingPeriodType.MONTHLY, LocalDate.of(2026, 12, 15))!!
        val january = confirm(RankingPeriodType.MONTHLY, LocalDate.of(2027, 1, 15))

        assertThat(december.periodStart).isEqualTo(LocalDate.of(2026, 12, 1))
        assertThat(december.slotCount).isEqualTo(2)
        assertThat(january).isNull()
    }

    @Test
    @DisplayName("연말·연초에 걸친 주(12/28~1/3)는 연도가 달라도 한 주로 집계된다")
    fun weekSpanningNewYear() {
        insertReservation(ReservationStatus.CONFIRMED, at(2026, 12, 30, 10, 0), at(2026, 12, 30, 10, 30))
        insertReservation(ReservationStatus.CONFIRMED, at(2027, 1, 2, 10, 0), at(2027, 1, 2, 10, 30))

        val week = confirm(RankingPeriodType.WEEKLY, LocalDate.of(2027, 1, 1))!!

        assertThat(week.periodStart).isEqualTo(LocalDate.of(2026, 12, 28))
        assertThat(week.reservationCount).isEqualTo(2)
    }

    @Test
    @DisplayName("일간 값 7개의 합이 주간 값과 같다(현금주의로 환불이 끼어 있어도)")
    fun dailySumsEqualWeekly() {
        val monday = at(2026, 10, 12, 10, 0)
        for (day in 0 until 7) {
            val start = monday.plusDays(day.toLong())
            val reservation = insertReservation(ReservationStatus.CONFIRMED, start, start.plusMinutes(30L * (day + 1)))
            charge(reservation, PRICE * (day + 1), start.minusDays(1))
        }
        val cancelled = insertReservation(ReservationStatus.CANCELLED, at(2026, 10, 25, 10, 0), at(2026, 10, 25, 10, 30))
        charge(cancelled, PRICE, at(2026, 10, 14, 9, 0))
        ledger(cancelled, "REFUND", PRICE, at(2026, 10, 16, 9, 0))

        var dailyRevenue = 0L
        var dailyCount = 0
        var dailySlots = 0
        for (day in 0 until 7) {
            val row = confirmDaily(LocalDate.of(2026, 10, 12).plusDays(day.toLong()))
            if (row != null) {
                dailyRevenue += row.netRevenue
                dailyCount += row.reservationCount
                dailySlots += row.slotCount
            }
        }
        val week = confirm(RankingPeriodType.WEEKLY, LocalDate.of(2026, 10, 12))!!

        assertThat(week.netRevenue).isEqualTo(dailyRevenue)
        assertThat(week.reservationCount).isEqualTo(dailyCount)
        assertThat(week.slotCount).isEqualTo(dailySlots)
    }

    // ---------- 저장 규칙 ----------

    @Test
    @DisplayName("비활성(INACTIVE) 공간의 과거 실적도 포함되고 계산 당시 location이 저장된다")
    fun inactiveSpaceIncludedWithLocationAtComputeTime() {
        val start = at(2026, 3, 10, 10, 0)
        insertReservation(ReservationStatus.COMPLETED, start, start.plusMinutes(30))
        jdbcTemplate.update("UPDATE spaces SET status = 'INACTIVE' WHERE id = ?", spaceId)

        val row = confirmDaily(LocalDate.of(2026, 3, 10))!!

        assertThat(row.location).isEqualTo("판교")
        assertThat(row.spaceId).isEqualTo(spaceId)
    }

    @Test
    @DisplayName("같은 기간을 두 번 계산해도 행이 중복되지 않고 값이 같다(멱등)")
    fun confirmingTwiceIsIdempotent() {
        val start = at(2026, 3, 10, 10, 0)
        val reservation = insertReservation(ReservationStatus.CONFIRMED, start, start.plusMinutes(60))
        charge(reservation, 2 * PRICE, start.minusHours(2))
        val day = LocalDate.of(2026, 3, 10)

        confirmDaily(day)
        confirmDaily(day)

        val rows = snapshotsOf(RankingPeriodType.DAILY, day)
        assertThat(rows).hasSize(1)
        assertThat(rows[0].reservationCount).isEqualTo(1)
        assertThat(rows[0].netRevenue).isEqualTo(2L * PRICE)
    }

    @Test
    @DisplayName("다시 계산하면 이전에 저장된 행이 새 결과로 덮어써지고, 더는 0이 아닌 공간의 행은 사라진다")
    fun recomputeOverwritesAndDropsStaleRows() {
        val start = at(2026, 3, 10, 10, 0)
        val reservation = insertReservation(ReservationStatus.CONFIRMED, start, start.plusMinutes(60))
        charge(reservation, 2 * PRICE, start.minusDays(1))
        val day = LocalDate.of(2026, 3, 10)
        confirmDaily(day)

        jdbcTemplate.update(
            "UPDATE reservation SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
            Timestamp.valueOf(start.minusHours(3)),
            reservation,
        )
        jdbcTemplate.update("DELETE FROM credit_transaction WHERE reservation_id = ?", reservation)
        confirmDaily(day)

        assertThat(snapshotsOf(RankingPeriodType.DAILY, day)).isEmpty()
    }

    @Test
    @DisplayName("computed_at에는 주입된 Clock의 현재 시각(KST)이 저장된다")
    fun computedAtUsesInjectedClock() {
        insertReservation(ReservationStatus.CONFIRMED, at(2026, 3, 10, 10, 0), at(2026, 3, 10, 10, 30))

        val row = confirmDaily(LocalDate.of(2026, 3, 10))!!

        assertThat(row.computedAt).isEqualTo(LocalDateTime.of(2027, 3, 1, 0, 1, 0))
    }

    @Test
    @DisplayName("거래가 없는 빈 기간은 행을 만들지 않는다")
    fun emptyPeriodSavesNothing() {
        assertThat(service.confirm(RankingPeriodType.DAILY, LocalDate.of(2026, 3, 10))).isZero()
    }

    // ---------- 확정 가능 기간 가드 ----------

    @Test
    @DisplayName("아직 끝나지 않은 기간(오늘 포함)은 확정할 수 없다")
    fun cannotConfirmPeriodThatHasNotEnded() {
        settableClock.set(ZonedDateTime.of(2026, 10, 16, 0, 1, 0, 0, KST))

        assertThatThrownBy { service.confirm(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 16)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.confirm(RankingPeriodType.WEEKLY, LocalDate.of(2026, 10, 14)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.confirm(RankingPeriodType.MONTHLY, LocalDate.of(2026, 10, 1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        // 어제(끝난 일간)는 확정할 수 있다
        assertThat(service.confirm(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 15))).isZero()
    }

    @Test
    @DisplayName("기간 끝 날짜 00:01 전에는 끝난 기간도 확정할 수 없고 00:01부터 가능하다(커밋 지연 완충)")
    fun cannotConfirmBeforeOneMinuteAfterPeriodEnd() {
        settableClock.set(ZonedDateTime.of(2026, 10, 16, 0, 0, 59, 0, KST))
        assertThatThrownBy { service.confirm(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 15)) }
            .isInstanceOf(IllegalArgumentException::class.java)

        settableClock.set(ZonedDateTime.of(2026, 10, 16, 0, 1, 0, 0, KST))
        assertThat(service.confirm(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 15))).isZero()
    }

    // ---------- helpers ----------

    private fun confirmDaily(date: LocalDate): SpaceRankingSnapshot? = confirm(RankingPeriodType.DAILY, date)

    private fun confirm(
        type: RankingPeriodType,
        date: LocalDate,
    ): SpaceRankingSnapshot? {
        service.confirm(type, date)
        return snapshotsOf(type, type.startOf(date)!!).firstOrNull { it.spaceId == spaceId }
    }

    private fun snapshotsOf(
        type: RankingPeriodType,
        periodDate: LocalDate,
    ): List<SpaceRankingSnapshot> =
        snapshotRepository.findAll().filter {
            it.periodType == type && it.periodStart == type.startOf(periodDate) && it.spaceId == spaceId
        }

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): LocalDateTime = LocalDateTime.of(year, month, day, hour, minute)

    private fun insertReservation(
        status: ReservationStatus,
        start: LocalDateTime,
        end: LocalDateTime,
    ): Long {
        val startTs = Timestamp.valueOf(start)
        jdbcTemplate.update(
            """
            INSERT INTO reservation (member_id, space_id, start_time, end_time, status,
                price_per_slot_snapshot, total_amount, hold_expires_at, checked_in_at,
                checked_out_at, cancelled_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            memberId,
            spaceId,
            startTs,
            Timestamp.valueOf(end),
            status.name,
            PRICE,
            PRICE,
            if (status == ReservationStatus.HELD) Timestamp.valueOf(start.minusHours(1)) else null,
            if (status == ReservationStatus.IN_USE || status == ReservationStatus.COMPLETED) startTs else null,
            if (status == ReservationStatus.COMPLETED) Timestamp.valueOf(end) else null,
            if (status == ReservationStatus.CANCELLED) Timestamp.valueOf(start.minusHours(3)) else null,
            Timestamp.valueOf(start.minusDays(30)),
        )
        return jdbcTemplate.queryForObject(
            "SELECT MAX(id) FROM reservation WHERE space_id = ?",
            Long::class.javaObjectType,
            spaceId,
        )!!
    }

    private fun charge(
        reservationId: Long,
        amount: Int,
        at: LocalDateTime,
    ) {
        ledger(reservationId, "RESERVATION_CHARGE", -amount, at)
    }

    private fun ledger(
        reservationId: Long,
        type: String,
        signedAmount: Int,
        at: LocalDateTime,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO credit_transaction (member_id, amount, type, reservation_id, balance_after, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            memberId,
            signedAmount,
            type,
            reservationId,
            0,
            Timestamp.valueOf(at),
        )
    }

    companion object {
        private val KST = SettableClockConfig.KST
        private const val PRICE = 5000
    }
}
