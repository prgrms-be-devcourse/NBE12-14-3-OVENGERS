package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.support.SettableClockConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 예약 취소 시각 경계에 따른 환불·위약금 처리를 실제 DB에서 검증한다.
 *
 * 1. 시작 정확히 1시간 전: 전액 환불
 * 2. 시작 1시간 전 + 1초: 50% 위약금
 * 3. 예약 시작 정각: 취소 거절
 *
 * 테스트용 Clock으로 시간을 고정하고 잔액, 원장, 슬롯, 이력, Outbox를 검증한다.
 */
@Import(SettableClockConfig::class)
class ReservationCancelBoundaryIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var reservationCancelService: ReservationCancelService

    @Autowired
    private lateinit var testClock: SettableClockConfig.SettableClock

    private var memberId: Long = 0L
    private var spaceId: Long = 0L

    @BeforeEach
    fun setUp() {
        setNow(START_TIME.minusHours(2))
        memberId = createMember(INITIAL_BALANCE)
        spaceId = createSpace()
    }

    @Test
    @DisplayName("시작 정확히 1시간 전에 취소하면 전액 환불되고 위약금 원장은 생성되지 않는다")
    fun cancel_exactlyOneHourBefore_recordsFullRefundOnly() {
        val reservationId = createConfirmedReservation()

        setNow(START_TIME.minusHours(1))

        val response = reservationCancelService.cancel(memberId, reservationId)

        assertThat(response.status).isEqualTo("CANCELLED")
        assertThat(response.refundAmount).isEqualTo(TOTAL_AMOUNT)
        assertThat(response.penaltyAmount).isZero()

        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE + TOTAL_AMOUNT)
        assertThat(countLedger(reservationId, "REFUND")).isEqualTo(1)
        assertThat(sumLedger(reservationId, "REFUND")).isEqualTo(TOTAL_AMOUNT)
        assertThat(countLedger(reservationId, "PENALTY")).isZero()
        assertThat(countSlots(reservationId)).isZero()
        assertThat(countHistory(reservationId, "CANCELLED")).isEqualTo(1)
        assertThat(countOutboxEvents(reservationId)).isEqualTo(1)
    }

    @Test
    @DisplayName("시작 1시간 전을 1초 넘기면 환불과 위약금 원장이 각각 기록된다")
    fun cancel_oneSecondAfterDeadline_recordsRefundAndPenalty() {
        val reservationId = createConfirmedReservation()

        setNow(START_TIME.minusHours(1).plusSeconds(1))

        val response = reservationCancelService.cancel(memberId, reservationId)

        val penalty = TOTAL_AMOUNT / 2
        val refund = TOTAL_AMOUNT - penalty

        assertThat(response.status).isEqualTo("CANCELLED")
        assertThat(response.refundAmount).isEqualTo(refund)
        assertThat(response.penaltyAmount).isEqualTo(penalty)
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE + refund)
        assertThat(countLedger(reservationId, "REFUND")).isEqualTo(1)
        assertThat(sumLedger(reservationId, "REFUND")).isEqualTo(TOTAL_AMOUNT)
        assertThat(countLedger(reservationId, "PENALTY")).isEqualTo(1)
        assertThat(sumLedger(reservationId, "PENALTY")).isEqualTo(-penalty)

        val refundBalanceAfter =
            jdbcTemplate.queryForObject(
                """
                SELECT balance_after
                FROM credit_transaction
                WHERE reservation_id = ? AND type = 'REFUND'
                """.trimIndent(),
                Int::class.javaObjectType,
                reservationId,
            )

        val penaltyBalanceAfter =
            jdbcTemplate.queryForObject(
                """
                SELECT balance_after
                FROM credit_transaction
                WHERE reservation_id = ? AND type = 'PENALTY'
                """.trimIndent(),
                Int::class.javaObjectType,
                reservationId,
            )

        assertThat(refundBalanceAfter).isEqualTo(INITIAL_BALANCE + TOTAL_AMOUNT)
        assertThat(penaltyBalanceAfter).isEqualTo(INITIAL_BALANCE + refund)
        assertThat(countSlots(reservationId)).isZero()
        assertThat(countHistory(reservationId, "CANCELLED")).isEqualTo(1)
        assertThat(countOutboxEvents(reservationId)).isEqualTo(1)
    }

    @Test
    @DisplayName("예약 시작 정각에는 취소를 거절하고 잔액과 원장을 변경하지 않는다")
    fun cancel_atStartTime_rejectsWithoutSideEffects() {
        val reservationId = createConfirmedReservation()

        setNow(START_TIME)

        assertThatThrownBy { reservationCancelService.cancel(memberId, reservationId) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED")
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE)
        assertThat(countLedger(reservationId, "REFUND")).isZero()
        assertThat(countLedger(reservationId, "PENALTY")).isZero()
        assertThat(countSlots(reservationId)).isEqualTo(2)
        assertThat(countHistory(reservationId, "CANCELLED")).isZero()
        assertThat(countOutboxEvents(reservationId)).isZero()
    }

    @Test
    @DisplayName("동일 예약을 순차적으로 두 번 취소해도 환불과 이력이 중복 생성되지 않는다")
    fun cancel_twiceSequentially_refundsOnlyOnce() {
        // given: CONFIRMED 상태의 예약 생성
        val reservationId = createConfirmedReservation()

        // 예약 시작 정확히 1시간 전으로 시간 설정 (전액 환불 구간)
        setNow(START_TIME.minusHours(1))

        // when: 첫 번째 취소 요청
        val firstResponse =
            reservationCancelService.cancel(memberId, reservationId)

        // then: 첫 번째 취소가 정상 처리됐는지 확인
        assertThat(firstResponse.status).isEqualTo("CANCELLED")
        assertThat(firstResponse.refundAmount).isEqualTo(TOTAL_AMOUNT)
        assertThat(firstResponse.penaltyAmount).isZero()

        // 첫 번째 취소 이후 실제 DB 상태 기록
        val balanceAfterFirstCancel = balanceOf(memberId)
        val refundCountAfterFirstCancel = countLedger(reservationId, "REFUND")
        val refundSumAfterFirstCancel = sumLedger(reservationId, "REFUND")
        val penaltyCountAfterFirstCancel = countLedger(reservationId, "PENALTY")
        val historyCountAfterFirstCancel = countHistory(reservationId, "CANCELLED")
        val outboxCountAfterFirstCancel = countOutboxEvents(reservationId)

        // 첫 번째 취소의 정상 결과 검증
        assertThat(balanceAfterFirstCancel)
            .isEqualTo(INITIAL_BALANCE + TOTAL_AMOUNT)
        assertThat(refundCountAfterFirstCancel).isEqualTo(1)
        assertThat(refundSumAfterFirstCancel).isEqualTo(TOTAL_AMOUNT)
        assertThat(penaltyCountAfterFirstCancel).isZero()
        assertThat(historyCountAfterFirstCancel).isEqualTo(1)
        assertThat(outboxCountAfterFirstCancel).isEqualTo(1)
        assertThat(countSlots(reservationId)).isZero()

        // when: 동일 예약에 두 번째 취소 요청
        // then: 이미 취소된 예약이므로 예외가 발생해야 함
        assertThatThrownBy { reservationCancelService.cancel(memberId, reservationId) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.RESERVATION_STATE_CONFLICT,
            )

        // then: 두 번째 취소 이후 DB 상태가 변경되지 않았는지 확인
        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED")

        // 크레딧 중복 환불 방지
        assertThat(balanceOf(memberId)).isEqualTo(balanceAfterFirstCancel)
        assertThat(countLedger(reservationId, "REFUND"))
            .isEqualTo(refundCountAfterFirstCancel)
        assertThat(sumLedger(reservationId, "REFUND"))
            .isEqualTo(refundSumAfterFirstCancel)
        assertThat(countLedger(reservationId, "PENALTY"))
            .isEqualTo(penaltyCountAfterFirstCancel)

        // 예약 관련 데이터 중복 처리 방지
        assertThat(countSlots(reservationId)).isZero()
        assertThat(countHistory(reservationId, "CANCELLED"))
            .isEqualTo(historyCountAfterFirstCancel)
        assertThat(countOutboxEvents(reservationId))
            .isEqualTo(outboxCountAfterFirstCancel)
    }

    @Test
    @DisplayName("부분 환불 후 순차 재취소 시 환불과 위약금이 중복되지 않는다")
    fun cancel_twiceSequentially_partialRefundDoesNotDuplicate() {
        // given: 예약 시작 30분 전 (50% 위약금)
        val reservationId = createConfirmedReservation()
        setNow(START_TIME.minusMinutes(30))

        // when: 첫 번째 취소
        val firstResponse = reservationCancelService.cancel(memberId, reservationId)

        // then: 부분 환불이 정상적으로 처리되는지 확인
        val penalty = TOTAL_AMOUNT / 2
        val refund = TOTAL_AMOUNT - penalty

        assertThat(firstResponse.status).isEqualTo("CANCELLED")
        assertThat(firstResponse.refundAmount).isEqualTo(refund)
        assertThat(firstResponse.penaltyAmount).isEqualTo(penalty)

        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE + refund)
        assertThat(countLedger(reservationId, "REFUND")).isEqualTo(1)
        assertThat(sumLedger(reservationId, "REFUND")).isEqualTo(TOTAL_AMOUNT)
        assertThat(countLedger(reservationId, "PENALTY")).isEqualTo(1)
        assertThat(sumLedger(reservationId, "PENALTY")).isEqualTo(-penalty)
        assertThat(countSlots(reservationId)).isZero()
        assertThat(countHistory(reservationId, "CANCELLED")).isEqualTo(1)
        assertThat(countOutboxEvents(reservationId)).isEqualTo(1)

        // 첫 번째 취소 이후 상태 저장
        val balanceAfterFirst = balanceOf(memberId)

        // when: 두 번째 취소
        assertThatThrownBy { reservationCancelService.cancel(memberId, reservationId) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.RESERVATION_STATE_CONFLICT,
            )

        // then: 두 번째 취소로 데이터가 변경되지 않는지 검증
        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED")
        assertThat(balanceOf(memberId)).isEqualTo(balanceAfterFirst)
        assertThat(countLedger(reservationId, "REFUND")).isEqualTo(1)
        assertThat(countLedger(reservationId, "PENALTY")).isEqualTo(1)
        assertThat(sumLedger(reservationId, "REFUND")).isEqualTo(TOTAL_AMOUNT)
        assertThat(sumLedger(reservationId, "PENALTY")).isEqualTo(-penalty)
        assertThat(countSlots(reservationId)).isZero()
        assertThat(countHistory(reservationId, "CANCELLED")).isEqualTo(1)
        assertThat(countOutboxEvents(reservationId)).isEqualTo(1)
    }

    private fun createConfirmedReservation(): Long =
        createReservation(
            memberId,
            spaceId,
            ReservationStatus.CONFIRMED,
            START_TIME,
            START_TIME.plusHours(1),
        )

    private fun setNow(dateTime: LocalDateTime) {
        testClock.set(
            ZonedDateTime.of(dateTime, SettableClockConfig.KST),
        )
    }

    private fun countOutboxEvents(reservationId: Long): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM outbox_event
                WHERE JSON_EXTRACT(payload, '$.reservationId') = ?
                """.trimIndent(),
                Int::class.javaObjectType,
                reservationId,
            ),
        )

    private companion object {
        const val INITIAL_BALANCE = 3_000
        const val TOTAL_AMOUNT = 10_000

        val START_TIME: LocalDateTime = LocalDateTime.of(2027, 3, 1, 14, 0)
    }
}
