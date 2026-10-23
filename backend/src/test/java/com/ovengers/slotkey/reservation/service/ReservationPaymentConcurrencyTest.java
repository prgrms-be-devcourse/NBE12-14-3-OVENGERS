package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결제 확인(POST /reservations/{id}/pay)이 반복·동시 요청에서도 크레딧을 정확히 1회만 차감하는지 실제 DB로 검증한다
 * (core-domain-decisions 2-1, 6-2, 12). 차감 -> 조건부 UPDATE 순서와 "0행이면 전체 롤백" 보장이 대상이다.
 */
class ReservationPaymentConcurrencyTest extends ReservationIntegrationTestSupport {

    private static final int INITIAL_BALANCE = 100_000;
    private static final int THREADS = 5;

    @Autowired
    private ReservationHoldService reservationHoldService;
    @Autowired
    private ReservationPaymentConfirmService reservationPaymentConfirmService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long memberId;
    private ReservationResponse hold;

    @BeforeEach
    void setUp() {
        memberId = createMember(INITIAL_BALANCE);
        Long spaceId = createSpace();
        hold = reservationHoldService.createHold(memberId, spaceId, tomorrowAt(14, 0), tomorrowAt(15, 0));
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 결제를 3번 순차 요청해도 크레딧은 1회만 차감되고 매번 같은 결과를 돌려받는다")
    void pay_sameKeyRepeated_chargesOnce() {
        ReservationResponse first = pay("key-1");
        ReservationResponse second = pay("key-1");
        ReservationResponse third = pay("key-1");

        assertThat(List.of(first, second, third))
                .extracting(ReservationResponse::status)
                .containsOnly("CONFIRMED");
        assertThat(List.of(second, third))
                .extracting(ReservationResponse::reservationId)
                .containsOnly(first.reservationId());
        assertChargedExactlyOnce();
        assertThat(countIdempotencyKeys(memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 예약에 서로 다른 Idempotency-Key로 결제가 동시에 들어와도 정확히 1건만 성공하고 크레딧은 1회만 차감된다")
    void pay_differentKeysConcurrently_chargesOnce() {
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String key = "key-" + i;
            tasks.add(() -> pay(key));
        }

        List<Throwable> results = runConcurrently(tasks);

        assertThat(successCount(results)).isEqualTo(1);
        assertThat(failures(results))
                .hasSize(THREADS - 1)
                .containsOnly(ErrorCode.RESERVATION_STATE_CONFLICT);
        assertChargedExactlyOnce();
        assertThat(countHistory(hold.reservationId(), "CONFIRMED")).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 예약을 동시에 결제해도 회원 잔액을 초과하여 차감하지 않는다")
    void pay_differentReservationsConcurrently_preventsOverdraft() {
        Long anotherSpaceId = createSpace();

        ReservationResponse anotherHold = reservationHoldService.createHold(
                memberId,
                anotherSpaceId,
                tomorrowAt(16, 0),
                tomorrowAt(17, 0)
        );

        // 각 예약은 10,000원이고, 두 예약을 모두 결제하기에는 잔액이 부족한 상태
        int chargeAmount = hold.totalAmount();
        int balanceBefore = chargeAmount + PRICE_PER_SLOT;

        assertThat(anotherHold.totalAmount()).isEqualTo(chargeAmount);
        setBalance(memberId, balanceBefore);

        // 서로 다른 멱등키로 두 예약을 동시에 결제
        List<Runnable> tasks = List.of(
                () -> reservationPaymentConfirmService.confirm(
                        memberId,
                        hold.reservationId(),
                        hold.spaceVersion(),
                        "overdraft-key-1"
                ),
                () -> reservationPaymentConfirmService.confirm(
                        memberId,
                        anotherHold.reservationId(),
                        anotherHold.spaceVersion(),
                        "overdraft-key-2"
                )
        );

        List<Throwable> results = runConcurrently(tasks);


        assertThat(successCount(results)).isEqualTo(1);
        assertThat(failures(results)).containsExactly(ErrorCode.INSUFFICIENT_BALANCE);
        assertThat(balanceOf(memberId)).isEqualTo(balanceBefore - chargeAmount);
        assertThat(List.of(statusOf(hold.reservationId()), statusOf(anotherHold.reservationId()))).containsExactlyInAnyOrder("CONFIRMED", "HELD");

        // 성공한 예약에만 차감 원장이 기록됐는지 확인
        for (ReservationResponse target : List.of(hold, anotherHold)) {
            Long reservationId = target.reservationId();

            if ("CONFIRMED".equals(statusOf(reservationId))) {
                assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isEqualTo(1);
                assertThat(sumLedger(reservationId, "RESERVATION_CHARGE")).isEqualTo(-chargeAmount);
                assertThat(countHistory(reservationId, "CONFIRMED")).isEqualTo(1);
            } else {
                assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isZero();
                assertThat(countHistory(reservationId, "CONFIRMED")).isZero();
            }
        }

        assertThat(countIdempotencyKeys(memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 Idempotency-Key라도 서로 다른 예약이면 각각 결제된다")
    void pay_sameKeyForDifferentReservations_chargesBoth() {
        // 같은 회원의 서로 다른 예약 준비
        Long anotherSpaceId = createSpace();

        ReservationResponse anotherHold = reservationHoldService.createHold(
                memberId,
                anotherSpaceId,
                tomorrowAt(16, 0),
                tomorrowAt(17, 0)
        );

        int totalAmount = hold.totalAmount() + anotherHold.totalAmount();

        // 서로 다른 예약에 동일한 멱등키 사용
        ReservationResponse first = reservationPaymentConfirmService.confirm(
                memberId,
                hold.reservationId(),
                hold.spaceVersion(),
                "same-key-different-reservation"
        );

        ReservationResponse second = reservationPaymentConfirmService.confirm(
                memberId,
                anotherHold.reservationId(),
                anotherHold.spaceVersion(),
                "same-key-different-reservation"
        );

        assertThat(first.status()).isEqualTo("CONFIRMED");
        assertThat(second.status()).isEqualTo("CONFIRMED");
        assertThat(first.reservationId()).isNotEqualTo(second.reservationId());
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE - totalAmount);
        assertThat(countLedger(hold.reservationId(), "RESERVATION_CHARGE")).isEqualTo(1);
        assertThat(countLedger(anotherHold.reservationId(), "RESERVATION_CHARGE")).isEqualTo(1);
        assertThat(sumLedger(hold.reservationId(), "RESERVATION_CHARGE")).isEqualTo(-hold.totalAmount());
        assertThat(sumLedger(anotherHold.reservationId(), "RESERVATION_CHARGE")).isEqualTo(-anotherHold.totalAmount());
        assertThat(countIdempotencyKeys(memberId)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 결제가 동시에 들어와도 크레딧은 1회만 차감되고, 이후 같은 키 재요청은 최초 응답을 그대로 돌려준다")
    void pay_sameKeyConcurrently_chargesOnceAndReplays() {
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> pay("shared-key"));
        }

        List<Throwable> results = runConcurrently(tasks);

        // 먼저 커밋된 요청이 있으면 늦게 온 요청은 저장된 응답을 재생받아 성공할 수 있다.
        // 밀린 요청은 409로 끝나며, 어느 경우에도 차감은 1회다.
        assertThat(successCount(results)).isGreaterThanOrEqualTo(1);
        assertThat(failures(results)).isSubsetOf(ErrorCode.RESERVATION_STATE_CONFLICT);
        assertChargedExactlyOnce();

        ReservationResponse replayed = pay("shared-key");

        assertThat(replayed.status()).isEqualTo("CONFIRMED");
        assertChargedExactlyOnce();
        assertThat(countIdempotencyKeys(memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("잔액 부족으로 결제가 실패하면 Idempotency-Key가 소진되지 않아, 충전 후 같은 키로 재시도하면 성공한다")
    void pay_insufficientBalance_doesNotConsumeIdempotencyKey() {
        setBalance(memberId, 0);

        assertThatThrownBy(() -> pay("retry-key"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_BALANCE);

        assertThat(statusOf(hold.reservationId())).isEqualTo("HELD");
        assertThat(countIdempotencyKeys(memberId)).isZero();
        assertThat(countLedger(hold.reservationId(), "RESERVATION_CHARGE")).isZero();

        setBalance(memberId, INITIAL_BALANCE);
        ReservationResponse retried = pay("retry-key");

        assertThat(retried.status()).isEqualTo("CONFIRMED");
        assertChargedExactlyOnce();
        assertThat(countIdempotencyKeys(memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 확정 조건부 UPDATE는 hold_expires_at 정각에는 확정하지 않고 1초 전까지만 확정한다(만료 쿼리의 <= 와 경계가 맞물린다)")
    void confirmIfHeldAndNotExpired_atExactExpiry_doesNotConfirm() {
        // DB에는 초 단위로 저장되므로, 응답 객체가 아니라 DB에 실제 저장된 값을 기준으로 삼는다.
        LocalDateTime expiresAt = reservationRepository.findById(hold.reservationId())
                .orElseThrow()
                .getHoldExpiresAt();

        Integer atExpiry = transactionTemplate.execute(status -> reservationRepository.confirmIfHeldAndNotExpired(
                hold.reservationId(), expiresAt, ReservationStatus.HELD, ReservationStatus.CONFIRMED));
        assertThat(atExpiry).isZero();
        assertThat(statusOf(hold.reservationId())).isEqualTo("HELD");

        Integer oneSecondBefore = transactionTemplate.execute(status -> reservationRepository.confirmIfHeldAndNotExpired(
                hold.reservationId(), expiresAt.minusSeconds(1), ReservationStatus.HELD, ReservationStatus.CONFIRMED));
        assertThat(oneSecondBefore).isEqualTo(1);
        assertThat(statusOf(hold.reservationId())).isEqualTo("CONFIRMED");
    }

    private ReservationResponse pay(String idempotencyKey) {
        return reservationPaymentConfirmService.confirm(
                memberId, hold.reservationId(), hold.spaceVersion(), idempotencyKey);
    }

    /** 예약이 CONFIRMED이고, 잔액이 정확히 총액만큼 줄었으며, 결제 원장이 1건뿐이다. */
    private void assertChargedExactlyOnce() {
        assertThat(statusOf(hold.reservationId())).isEqualTo("CONFIRMED");
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE - hold.totalAmount());
        assertThat(countLedger(hold.reservationId(), "RESERVATION_CHARGE")).isEqualTo(1);
        assertThat(sumLedger(hold.reservationId(), "RESERVATION_CHARGE")).isEqualTo(-hold.totalAmount());
    }
}