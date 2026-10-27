package com.ovengers.slotkey.reservation.scheduler

import com.ovengers.slotkey.credit.service.CreditService
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ReservationNoShowCreditIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var reservationBatchProcessor: ReservationBatchProcessor

    @Autowired
    private lateinit var creditService: CreditService

    @Test
    @DisplayName("결제된 예약을 노쇼 처리해도 크레딧 잔액은 유지되고 환불 원장은 생성되지 않는다")
    fun markNoShow_paidReservation_doesNotRefundCredits() {
        // given: 회원과 공간 생성
        val memberId = createMember(INITIAL_BALANCE)
        val spaceId = createSpace()

        // 테스트 실행일 기준 내일 오후 2시
        // 실제 스케줄러가 테스트보다 먼저 노쇼 처리하지 않도록 미래 시각 사용
        val startTime = tomorrowAt(14, 0)

        // 확정 예약 생성 (1시간 = 30분 슬롯 2개)
        val reservationId =
            createReservation(
                memberId,
                spaceId,
                ReservationStatus.CONFIRMED,
                startTime,
                startTime.plusHours(1),
            )

        // 실제 크레딧 차감 및 거래 원장 저장
        val balanceAfterPayment =
            creditService.charge(
                memberId,
                reservationId,
                TOTAL_AMOUNT,
            )

        // 결제 직후 상태 검증
        assertThat(balanceAfterPayment)
            .isEqualTo(INITIAL_BALANCE - TOTAL_AMOUNT)

        assertThat(balanceOf(memberId))
            .isEqualTo(balanceAfterPayment)

        assertThat(countLedger(reservationId, "RESERVATION_CHARGE"))
            .isEqualTo(1)

        assertThat(sumLedger(reservationId, "RESERVATION_CHARGE"))
            .isEqualTo(-TOTAL_AMOUNT)

        assertThat(countLedger(reservationId, "REFUND"))
            .isZero()

        // when: 예약 시작 15분 1초 후 노쇼 처리
        val noShowAt = startTime.plusMinutes(15).plusSeconds(1)

        val processed =
            reservationBatchProcessor.markNoShow(
                reservationId,
                noShowAt,
            )

        // then: 예약 상태가 NO_SHOW로 변경됨
        assertThat(processed).isTrue()

        assertThat(statusOf(reservationId))
            .isEqualTo("NO_SHOW")

        // 예약 슬롯 삭제
        assertThat(countSlots(reservationId))
            .isZero()

        // 상태 변경 이력 1건 생성
        assertThat(countHistory(reservationId, "NO_SHOW"))
            .isEqualTo(1)

        // 노쇼 이후에도 결제 후 잔액이 유지되어야 함
        assertThat(balanceOf(memberId))
            .isEqualTo(balanceAfterPayment)

        // 기존 결제 원장은 그대로 유지되어야 함
        assertThat(countLedger(reservationId, "RESERVATION_CHARGE"))
            .isEqualTo(1)

        assertThat(sumLedger(reservationId, "RESERVATION_CHARGE"))
            .isEqualTo(-TOTAL_AMOUNT)

        // 노쇼는 환불이나 추가 위약금을 발생시키지 않음
        assertThat(countLedger(reservationId, "REFUND"))
            .isZero()

        assertThat(countLedger(reservationId, "PENALTY"))
            .isZero()
    }

    private companion object {
        const val INITIAL_BALANCE = 20_000
        const val TOTAL_AMOUNT = 10_000
    }
}
