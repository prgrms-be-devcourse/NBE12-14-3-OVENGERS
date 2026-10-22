package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 예약에 취소가 겹쳐도 환불이 정확히 1회만 일어나는지 실제 DB로 검증한다(core-domain-decisions 6-2).
 * 예약자 본인만 취소할 수 있으므로 "서로 다른 사용자끼리의 경합"이 아니라 다음 두 경우다.
 * 1) 같은 사용자의 중복 요청(더블 클릭, 탭 두 개, 네트워크 재시도)
 * 2) 사용자 본인 취소 ↔ 관리자 강제 취소가 같은 순간에 처리되는 경우
 */
class ReservationCancelConcurrencyTest extends ReservationIntegrationTestSupport {

    private static final int INITIAL_BALANCE = 3_000;
    private static final int TOTAL_AMOUNT = 10_000; // 30분 슬롯 2개 × 5,000원
    private static final int THREADS = 5;
    private static final int ROUNDS = 5;

    @Autowired
    private ReservationCancelService reservationCancelService;
    @Autowired
    private AdminReservationService adminReservationService;

    private Long memberId;
    private Long adminId;
    private Long spaceId;

    @BeforeEach
    void setUp() {
        memberId = createMember(INITIAL_BALANCE);
        adminId = createMember(0);
        spaceId = createSpace();
    }

    @Test
    @DisplayName("같은 사용자가 같은 예약을 동시에 여러 번 취소해도 정확히 1건만 성공하고 환불은 1회만 일어난다")
    void cancel_sameUserConcurrently_refundsOnce() {
        Long reservationId = createReservation(
                memberId, spaceId, ReservationStatus.CONFIRMED, tomorrowAt(14, 0), tomorrowAt(15, 0));
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> reservationCancelService.cancel(memberId, reservationId));
        }

        List<Throwable> results = runConcurrently(tasks);

        assertThat(successCount(results)).isEqualTo(1);
        assertThat(failures(results))
                .hasSize(THREADS - 1)
                .containsOnly(ErrorCode.RESERVATION_STATE_CONFLICT);
        assertRefundedExactlyOnce(reservationId, INITIAL_BALANCE);
    }

    @Test
    @DisplayName("사용자 본인 취소와 관리자 강제 취소가 동시에 처리돼도 정확히 한쪽만 성공하고 환불은 1회만 일어난다")
    void cancel_userAndAdminConcurrently_refundsOnce() {
        // 두 요청이 실제로 겹칠 확률을 높이기 위해 서로 다른 예약으로 여러 판 반복한다.
        for (int round = 0; round < ROUNDS; round++) {
            int balanceBefore = balanceOf(memberId);
            Long reservationId = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED,
                    tomorrowAt(9 + round * 2, 0), tomorrowAt(10 + round * 2, 0));
            List<Runnable> tasks = List.of(
                    () -> reservationCancelService.cancel(memberId, reservationId),
                    () -> adminReservationService.forceCancel(reservationId, "동시 취소 경합 검증", adminId));

            List<Throwable> results = runConcurrently(tasks);

            assertThat(successCount(results)).as("round %d", round).isEqualTo(1);
            assertThat(failures(results)).as("round %d", round)
                    .containsExactly(ErrorCode.RESERVATION_STATE_CONFLICT);
            assertRefundedExactlyOnce(reservationId, balanceBefore);
        }
    }

    @Test
    @DisplayName("부분 환불을 동시에 요청해도 환불과 위약금 원장이 각각 한 번만 기록된다")
    void cancel_partialRefundConcurrently_recordsRefundAndPenaltyOnce() {
        // 예약 시작 30분 전 상황 준비
        LocalDateTime startTime = now().plusMinutes(30);

        Long reservationId = createReservation(
                memberId,
                spaceId,
                ReservationStatus.CONFIRMED,
                startTime,
                startTime.plusHours(1)
        );

        // 같은 예약에 대한 취소 요청 두 건을 동시에 실행
        List<Runnable> tasks = List.of(
                () -> reservationCancelService.cancel(memberId, reservationId),
                () -> reservationCancelService.cancel(memberId, reservationId)
        );

        List<Throwable> results = runConcurrently(tasks);

        assertThat(successCount(results)).isEqualTo(1);
        assertThat(failures(results)).containsExactly(ErrorCode.RESERVATION_STATE_CONFLICT);
        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED");
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE + TOTAL_AMOUNT / 2);
        assertThat(countLedger(reservationId, "REFUND")).isEqualTo(1);
        assertThat(sumLedger(reservationId, "REFUND")).isEqualTo(TOTAL_AMOUNT);
        assertThat(countLedger(reservationId, "PENALTY")).isEqualTo(1);
        assertThat(sumLedger(reservationId, "PENALTY")).isEqualTo(-TOTAL_AMOUNT / 2);

        // 각 원장에 저장된 처리 후 잔액 검증
        Integer refundBalanceAfter = jdbcTemplate.queryForObject(
                """
                SELECT balance_after
                FROM credit_transaction
                WHERE reservation_id = ? AND type = 'REFUND'
                """,
                Integer.class,
                reservationId
        );

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
        assertThat(penaltyBalanceAfter).isEqualTo(INITIAL_BALANCE + TOTAL_AMOUNT / 2);
        assertThat(countSlots(reservationId)).isZero();
        assertThat(countHistory(reservationId, "CANCELLED")).isEqualTo(1);
    }

    /** CANCELLED이고, 잔액이 정확히 총액만큼 늘었으며, 환불 원장 1건·위약금 없음·슬롯 전부 반환·취소 이력 1건이다. */
    private void assertRefundedExactlyOnce(Long reservationId, int balanceBefore) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE JSON_EXTRACT(payload, '$.reservationId') = ?",
                Integer.class, reservationId)).isEqualTo(1);
        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED");
        assertThat(balanceOf(memberId)).isEqualTo(balanceBefore + TOTAL_AMOUNT);
        assertThat(countLedger(reservationId, "REFUND")).isEqualTo(1);
        assertThat(sumLedger(reservationId, "REFUND")).isEqualTo(TOTAL_AMOUNT);
        assertThat(countLedger(reservationId, "PENALTY")).isZero();
        assertThat(countSlots(reservationId)).isZero();
        assertThat(countHistory(reservationId, "CANCELLED")).isEqualTo(1);
    }
}
