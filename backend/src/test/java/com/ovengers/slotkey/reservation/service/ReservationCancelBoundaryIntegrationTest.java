package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport;
import com.ovengers.slotkey.support.SettableClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예약 취소 시각 경계에 따른 환불·위약금 처리 결과를 실제 DB에서 검증한다.
 *
 * 1) 시작 정확히 1시간 전: 전액 환불, 위약금 없음
 * 2) 시작 1시간 전 + 1초: 50% 순환급, 위약금 발생
 * 3) 예약 시작 정각: 취소 거절, DB 상태 변경 없음
 *
 * 고정 Clock을 사용하여 테스트 실행 시각과 관계없이 동일한 결과를 검증한다.
 * 예약 상태, 회원 잔액, 크레딧 원장, 슬롯, 상태 이력, Outbox를 함께 확인한다.
 */
@Import(SettableClockConfig.class)
class ReservationCancelBoundaryIntegrationTest
        extends ReservationIntegrationTestSupport {

    private static final int INITIAL_BALANCE = 3_000;
    private static final int TOTAL_AMOUNT = 10_000;

    // 모든 테스트에서 공통으로 사용할 예약 시작 시각
    private static final LocalDateTime START_TIME =
            LocalDateTime.of(2027, 3, 1, 14, 0);

    @Autowired
    private ReservationCancelService reservationCancelService;

    // 테스트 중 현재 시각을 직접 변경할 수 있는 Clock
    @Autowired
    private SettableClockConfig.SettableClock testClock;

    private Long memberId;
    private Long spaceId;

    @BeforeEach
    void setUp() {
        // 1. 테스트 시작 시각을 예약 시작 2시간 전으로 초기화
        setNow(START_TIME.minusHours(2));

        // 2. 초기 잔액 3,000원을 가진 회원 생성
        memberId = createMember(INITIAL_BALANCE);

        // 3. 예약에 사용할 공간 생성
        spaceId = createSpace();
    }

    @Test
    @DisplayName("시작 정확히 1시간 전에 취소하면 전액 환불되고 위약금 원장은 생성되지 않는다")
    void cancel_exactlyOneHourBefore_recordsFullRefundOnly() {
        // 1. 총액 10,000원의 확정 예약 생성
        Long reservationId = createConfirmedReservation();

        // 2. 현재 시각을 예약 시작 정확히 1시간 전으로 설정
        // 환불 정책상 이 시각까지는 전액 환불 대상이다.
        setNow(START_TIME.minusHours(1));

        // 3. 실제 예약 취소 서비스 호출
        ReservationResponse response =
                reservationCancelService.cancel(memberId, reservationId);

        // 4. 취소 성공 및 응답 금액 검증
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.refundAmount()).isEqualTo(TOTAL_AMOUNT);
        assertThat(response.penaltyAmount()).isZero();

        // 5. 회원 잔액에 예약 금액 전액이 환급됐는지 확인
        assertThat(balanceOf(memberId))
                .isEqualTo(INITIAL_BALANCE + TOTAL_AMOUNT);

        // 6. REFUND 원장이 정확히 한 건 기록됐는지 확인
        assertThat(countLedger(reservationId, "REFUND"))
                .isEqualTo(1);

        assertThat(sumLedger(reservationId, "REFUND"))
                .isEqualTo(TOTAL_AMOUNT);

        // 7. 전액 환불이므로 PENALTY 원장은 없어야 한다.
        assertThat(countLedger(reservationId, "PENALTY"))
                .isZero();

        // 8. 취소한 예약의 슬롯이 모두 반환됐는지 확인
        assertThat(countSlots(reservationId)).isZero();

        // 9. CANCELLED 상태 이력이 정확히 한 건 저장됐는지 확인
        assertThat(countHistory(reservationId, "CANCELLED"))
                .isEqualTo(1);

        // 10. 빈자리 알림 처리를 위한 Outbox 이벤트가 생성됐는지 확인
        assertThat(countOutboxEvents(reservationId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("시작 1시간 전을 1초 넘기면 환불과 위약금 원장이 각각 기록된다")
    void cancel_oneSecondAfterDeadline_recordsRefundAndPenalty() {
        // 총액 10,000원의 확정 예약 생성
        Long reservationId = createConfirmedReservation();

        // 전액 환불 마감 시각보다 정확히 1초 늦게 설정
        // 환불 정책상 50% 위약금이 발생하는 경계 시각이다.
        setNow(START_TIME.minusHours(1).plusSeconds(1));

        //  실제 예약 취소 서비스 호출
        ReservationResponse response = reservationCancelService.cancel(memberId, reservationId);

        //  예상 위약금과 최종 환급 금액 계산
        int penalty = TOTAL_AMOUNT / 2;
        int refund = TOTAL_AMOUNT - penalty;


        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.refundAmount()).isEqualTo(refund);
        assertThat(response.penaltyAmount()).isEqualTo(penalty);
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE + refund);
        assertThat(countLedger(reservationId, "REFUND")).isEqualTo(1);
        assertThat(sumLedger(reservationId, "REFUND")).isEqualTo(TOTAL_AMOUNT);
        assertThat(countLedger(reservationId, "PENALTY")).isEqualTo(1);
        assertThat(sumLedger(reservationId, "PENALTY")).isEqualTo(-penalty);

        // REFUND 거래가 처리된 직후의 잔액 조회
        Integer refundBalanceAfter = jdbcTemplate.queryForObject(
                """
                SELECT balance_after
                FROM credit_transaction
                WHERE reservation_id = ? AND type = 'REFUND'
                """,
                Integer.class,
                reservationId
        );

        // PENALTY 거래가 처리된 직후의 잔액 조회
        Integer penaltyBalanceAfter = jdbcTemplate.queryForObject(
                """
                SELECT balance_after
                FROM credit_transaction
                WHERE reservation_id = ? AND type = 'PENALTY'
                """,
                Integer.class,
                reservationId
        );


        assertThat(refundBalanceAfter).isEqualTo(INITIAL_BALANCE + TOTAL_AMOUNT);
        assertThat(penaltyBalanceAfter).isEqualTo(INITIAL_BALANCE + refund);
        assertThat(countSlots(reservationId)).isZero();
        assertThat(countHistory(reservationId, "CANCELLED")).isEqualTo(1);
        assertThat(countOutboxEvents(reservationId)).isEqualTo(1);
    }

    @Test
    @DisplayName("예약 시작 정각에는 취소를 거절하고 잔액과 원장을 변경하지 않는다")
    void cancel_atStartTime_rejectsWithoutSideEffects() {
        // 총액 10,000원의 확정 예약 생성
        Long reservationId = createConfirmedReservation();

        // 현재 시각을 예약 시작 정각으로 설정
        // 예약이 이미 시작됐으므로 취소할 수 없어야 한다.
        setNow(START_TIME);

        // 취소 요청 시 예약 상태 충돌 예외가 발생해야 한다.
        assertThatThrownBy(
                () -> reservationCancelService.cancel(memberId, reservationId)
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.RESERVATION_STATE_CONFLICT
                );

        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED");
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE);
        assertThat(countLedger(reservationId, "REFUND")).isZero();
        assertThat(countLedger(reservationId, "PENALTY")).isZero();
        assertThat(countSlots(reservationId)).isEqualTo(2);
        assertThat(countHistory(reservationId, "CANCELLED")).isZero();
        assertThat(countOutboxEvents(reservationId)).isZero();
    }

    /**
     * 테스트에 사용할 CONFIRMED 예약을 생성한다.
     * 예약 시간은 14:00~15:00이며, 30분 슬롯 2개로 총액은 10,000원이다.
     */
    private Long createConfirmedReservation() {
        return createReservation(
                memberId,
                spaceId,
                ReservationStatus.CONFIRMED,
                START_TIME,
                START_TIME.plusHours(1)
        );
    }

    /**
     * 테스트의 현재 시각을 지정한다.
     * 실제 시스템 시계 대신 테스트용 Clock을 변경하여 시간 경계를 재현한다.
     */
    private void setNow(LocalDateTime dateTime) {
        testClock.set(
                ZonedDateTime.of(dateTime, SettableClockConfig.KST)
        );
    }

    /**
     * 특정 예약에서 발생한 Outbox 이벤트 개수를 실제 DB에서 조회한다.
     * 예약 취소 시 이벤트가 누락되거나 중복 생성되지 않았는지 검증하기 위해 사용한다.
     */
    private int countOutboxEvents(Long reservationId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM outbox_event
                WHERE JSON_EXTRACT(payload, '$.reservationId') = ?
                """,
                Integer.class,
                reservationId
        );
    }
}