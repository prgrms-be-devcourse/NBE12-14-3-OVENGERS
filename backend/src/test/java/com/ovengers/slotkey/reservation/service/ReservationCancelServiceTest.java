package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.access.service.DoorAccessTokenService;
import com.ovengers.slotkey.credit.service.CreditService;
import com.ovengers.slotkey.outbox.SlotReleasedRecorder;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.entity.MemberStatus;
import com.ovengers.slotkey.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservationCancelServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Long MEMBER_ID = 1L;
    private static final Long OTHER_MEMBER_ID = 2L;
    private static final Long RESERVATION_ID = 10L;
    private static final int TOTAL_AMOUNT = 20000;

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private ReservationSlotRepository reservationSlotRepository;
    @Mock
    private ReservationStatusHistoryRepository reservationStatusHistoryRepository;
    @Mock
    private CreditService creditService;
    @Mock
    private DoorAccessTokenService doorAccessTokenService;

    private Clock clock;
    @Mock
    private SlotReleasedRecorder slotReleasedRecorder;
    private ReservationCancelService cancelService;
    private LocalDateTime now;
    private LocalDateTime startTime;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.of(2026, 9, 17, 13, 0);
        clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
        cancelService = new ReservationCancelService(
                reservationRepository, memberRepository, reservationSlotRepository, reservationStatusHistoryRepository,
                creditService, doorAccessTokenService, clock, slotReleasedRecorder);
        startTime = LocalDateTime.of(2026, 9, 17, 14, 0); // now(13:00) + 1시간
    }

    private Member activeMember() {
        return new Member("user@slotkey.test", "회원");
    }

    private Reservation confirmedReservation(Long memberId) {
        return Reservation.builder()
                .id(RESERVATION_ID)
                .memberId(memberId)
                .spaceId(5L)
                .startTime(startTime)
                .endTime(startTime.plusHours(1))
                .status(ReservationStatus.CONFIRMED)
                .pricePerSlotSnapshot(5000)
                .totalAmount(TOTAL_AMOUNT)
                .createdAt(now.minusDays(1))
                .build();
    }

    @Test
    @DisplayName("존재하지 않는 예약이면 RESERVATION_NOT_FOUND 예외가 발생한다")
    void cancel_reservationNotFound_throwsException() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> cancelService.cancel(MEMBER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("본인이 아닌 예약을 취소 요청하면 FORBIDDEN_NOT_OWNER 예외가 발생하고 취소·환불이 실행되지 않는다")
    void cancel_notOwner_throwsException() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(confirmedReservation(MEMBER_ID)));

        assertThatThrownBy(() -> cancelService.cancel(OTHER_MEMBER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER);

        verify(reservationRepository, never()).cancelIfConfirmedAndBeforeStart(any(), any(), any(), any());
        verify(creditService, never()).refund(any(), any(), anyInt());
        verify(doorAccessTokenService, never()).revokeByReservation(any(), any(), any());
        verify(slotReleasedRecorder, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("취소할 수 없는 상태(이미 시작됨 등)이면 RESERVATION_STATE_CONFLICT 예외가 발생하고 슬롯·환불 처리를 하지 않는다")
    void cancel_conflictState_throwsException() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(confirmedReservation(MEMBER_ID)));
        given(reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID, now, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED))
                .willReturn(0);

        assertThatThrownBy(() -> cancelService.cancel(MEMBER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT);

        verify(reservationSlotRepository, never()).deleteByReservationId(any());
        verify(creditService, never()).refund(any(), any(), anyInt());
        verify(doorAccessTokenService, never()).revokeByReservation(any(), any(), any());
    }

    @Test
    @DisplayName("시작 정확히 1시간 전에 취소하면 전액 환불되고 위약금은 기록하지 않는다")
    void cancel_exactlyOneHourBefore_fullRefund() {
        Reservation reservation = confirmedReservation(MEMBER_ID);
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation), Optional.of(reservation));
        given(reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID, now, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED))
                .willReturn(1);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(activeMember()));

        ReservationResponse response = cancelService.cancel(MEMBER_ID, RESERVATION_ID);

        verify(creditService).refund(MEMBER_ID, RESERVATION_ID, TOTAL_AMOUNT);
        verify(creditService, never()).penalize(any(), any(), anyInt());
        assertThat(response.refundAmount()).isEqualTo(TOTAL_AMOUNT);
        assertThat(response.penaltyAmount()).isEqualTo(0);
    }

    @Test
    @DisplayName("시작 1시간 전 마감이 지난 뒤 취소하면 50%만 환불되고 나머지는 위약금으로 기록한다")
    void cancel_afterOneHourDeadline_halfRefund() {
        LocalDateTime lateNow = now.plusMinutes(1); // 13:01, 마감(13:00) 1분 경과
        Clock lateClock = Clock.fixed(lateNow.atZone(ZONE).toInstant(), ZONE);
        cancelService = new ReservationCancelService(
                reservationRepository, memberRepository, reservationSlotRepository, reservationStatusHistoryRepository,
                creditService, doorAccessTokenService, lateClock, slotReleasedRecorder);
        Reservation reservation = confirmedReservation(MEMBER_ID);
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation), Optional.of(reservation));
        given(reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID, lateNow, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED))
                .willReturn(1);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(activeMember()));

        ReservationResponse response = cancelService.cancel(MEMBER_ID, RESERVATION_ID);

        verify(creditService).refund(MEMBER_ID, RESERVATION_ID, TOTAL_AMOUNT);
        verify(creditService).penalize(MEMBER_ID, RESERVATION_ID, TOTAL_AMOUNT / 2);
        assertThat(response.refundAmount()).isEqualTo(TOTAL_AMOUNT / 2);
        assertThat(response.penaltyAmount()).isEqualTo(TOTAL_AMOUNT / 2);
    }

    @Test
    @DisplayName("정상 취소 시 잠금 순서(Reservation UPDATE -> Member X -> Slot DELETE -> Refund)를 준수하며 슬롯을 반환하고 상태 이력을 저장한다")
    void cancel_success_deletesSlotsAndSavesHistory() {
        Reservation reservation = confirmedReservation(MEMBER_ID);
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation), Optional.of(reservation));
        given(reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID, now, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED))
                .willReturn(1);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(activeMember()));

        cancelService.cancel(MEMBER_ID, RESERVATION_ID);

        InOrder inOrder = inOrder(reservationRepository, memberRepository, reservationSlotRepository, creditService);
        inOrder.verify(reservationRepository).cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID, now, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED);
        inOrder.verify(memberRepository).findByIdForUpdate(MEMBER_ID);
        inOrder.verify(reservationSlotRepository).deleteByReservationId(RESERVATION_ID);
        inOrder.verify(creditService).refund(MEMBER_ID, RESERVATION_ID, TOTAL_AMOUNT);

        verify(doorAccessTokenService).revokeByReservation(RESERVATION_ID, now, "CANCELLED");
        verify(reservationStatusHistoryRepository).save(argThat(history ->
                history.getFromStatus() == ReservationStatus.CONFIRMED
                        && history.getToStatus() == ReservationStatus.CANCELLED
                        && history.getChangedByMemberId().equals(MEMBER_ID)));
        verify(slotReleasedRecorder).record(RESERVATION_ID, 5L, SlotReleasedRecorder.Reason.CANCELLED);
    }

    @Test
    @DisplayName("회원이 존재하지 않으면 AUTHENTICATION_REQUIRED 예외가 발생하고 슬롯 삭제 및 환불이 실행되지 않는다")
    void cancel_memberNotFound_throwsAuthenticationRequired() {
        Reservation reservation = confirmedReservation(MEMBER_ID);
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation));
        given(reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID, now, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED))
                .willReturn(1);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> cancelService.cancel(MEMBER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTHENTICATION_REQUIRED);

        verify(reservationSlotRepository, never()).deleteByReservationId(any());
        verify(creditService, never()).refund(any(), any(), anyInt());
    }

    @Test
    @DisplayName("탈퇴한 회원이면 ACCOUNT_WITHDRAWN 예외가 발생하고 슬롯 삭제 및 환불이 실행되지 않는다")
    void cancel_withdrawnMember_throwsAccountWithdrawn() {
        Reservation reservation = confirmedReservation(MEMBER_ID);
        Member withdrawnMember = activeMember();
        withdrawnMember.withdraw(now);

        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation));
        given(reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID, now, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED))
                .willReturn(1);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(withdrawnMember));

        assertThatThrownBy(() -> cancelService.cancel(MEMBER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_WITHDRAWN);

        verify(reservationSlotRepository, never()).deleteByReservationId(any());
        verify(creditService, never()).refund(any(), any(), anyInt());
    }

    private Reservation heldReservation(ReservationStatus status) {
        return Reservation.builder()
                .id(RESERVATION_ID)
                .memberId(MEMBER_ID)
                .spaceId(5L)
                .startTime(startTime)
                .endTime(startTime.plusHours(1))
                .status(status)
                .pricePerSlotSnapshot(5000)
                .totalAmount(TOTAL_AMOUNT)
                .holdExpiresAt(now.plusMinutes(5))
                .createdAt(now.minusMinutes(5))
                .build();
    }

    @Test
    @DisplayName("결제 대기(HELD) 예약을 취소하면 슬롯을 반환하고 환불·위약금 없이 CANCELLED가 되며 빈자리 이벤트를 기록한다")
    void cancel_held_releasesSlotWithoutRefund() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(
                Optional.of(heldReservation(ReservationStatus.HELD)),
                Optional.of(heldReservation(ReservationStatus.CANCELLED)));
        given(reservationRepository.cancelIfHeld(
                RESERVATION_ID, now, ReservationStatus.HELD, ReservationStatus.CANCELLED))
                .willReturn(1);

        ReservationResponse response = cancelService.cancel(MEMBER_ID, RESERVATION_ID);

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.refundAmount()).isZero();
        assertThat(response.penaltyAmount()).isZero();
        verify(reservationSlotRepository).deleteByReservationId(RESERVATION_ID);
        verify(reservationStatusHistoryRepository).save(any());
        verify(slotReleasedRecorder).record(RESERVATION_ID, 5L, SlotReleasedRecorder.Reason.CANCELLED);
        verify(reservationRepository, never()).cancelIfConfirmedAndBeforeStart(any(), any(), any(), any());
        verify(creditService, never()).refund(any(), any(), anyInt());
        verify(creditService, never()).penalize(any(), any(), anyInt());
    }

    @Test
    @DisplayName("결제 대기 취소 중 이미 결제·만료로 상태가 바뀌었으면 RESERVATION_STATE_CONFLICT이고 슬롯·이벤트를 건드리지 않는다")
    void cancel_held_conflictWhenStateChanged() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(heldReservation(ReservationStatus.HELD)));
        given(reservationRepository.cancelIfHeld(
                RESERVATION_ID, now, ReservationStatus.HELD, ReservationStatus.CANCELLED))
                .willReturn(0);

        assertThatThrownBy(() -> cancelService.cancel(MEMBER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT);

        verify(reservationSlotRepository, never()).deleteByReservationId(any());
        verify(slotReleasedRecorder, never()).record(any(), any(), any());
    }
}
