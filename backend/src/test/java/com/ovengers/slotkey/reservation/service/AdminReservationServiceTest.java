package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.access.entity.AccessDenyReason;
import com.ovengers.slotkey.access.entity.AccessResult;
import com.ovengers.slotkey.access.entity.DoorAccessLog;
import com.ovengers.slotkey.access.service.DoorAccessLogService;
import com.ovengers.slotkey.access.service.DoorAccessTokenService;
import com.ovengers.slotkey.audit.entity.AuditAction;
import com.ovengers.slotkey.audit.entity.AuditTargetType;
import com.ovengers.slotkey.audit.service.AuditLogService;
import com.ovengers.slotkey.credit.service.CreditService;
import com.ovengers.slotkey.outbox.SlotReleasedRecorder;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.reservation.dto.request.AdminReservationSearchCondition;
import com.ovengers.slotkey.reservation.dto.response.AdminReservationDetailResponse;
import com.ovengers.slotkey.reservation.dto.response.AdminReservationResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminReservationServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Long RESERVATION_ID = 10L;
    private static final Long MEMBER_ID = 1L;
    private static final Long SPACE_ID = 5L;
    private static final Long ADMIN_MEMBER_ID = 99L;
    private static final String REASON = "고객 요청에 의한 강제 취소";

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationSlotRepository reservationSlotRepository;
    @Mock
    private ReservationStatusHistoryRepository statusHistoryRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private DoorAccessTokenService doorAccessTokenService;
    @Mock
    private DoorAccessLogService doorAccessLogService;
    @Mock
    private CreditService creditService;

    @Mock
    private SlotReleasedRecorder slotReleasedRecorder;
    private AdminReservationService adminReservationService;
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 17, 12, 0);
    private LocalDateTime startTime;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
        adminReservationService = new AdminReservationService(
                reservationRepository, reservationSlotRepository, statusHistoryRepository,
                memberRepository, spaceRepository, auditLogService,
                doorAccessTokenService, doorAccessLogService, creditService, clock, slotReleasedRecorder);
        startTime = LocalDateTime.of(2026, 9, 18, 14, 0);
    }

    private Reservation confirmedReservation() {
        return reservationWithStatus(ReservationStatus.CONFIRMED, 10000);
    }

    private Reservation reservationWithStatus(ReservationStatus status, int totalAmount) {
        return Reservation.builder()
                .id(RESERVATION_ID)
                .memberId(MEMBER_ID)
                .spaceId(SPACE_ID)
                .startTime(startTime)
                .endTime(startTime.plusHours(1))
                .status(status)
                .pricePerSlotSnapshot(5000)
                .totalAmount(totalAmount)
                .createdAt(startTime.minusDays(1))
                .build();
    }

    /** 응답 조립에 쓰이는 회원 이메일·공간 이름 조회를 준비한다. */
    private void stubMemberAndSpace() {
        given(memberRepository.findById(MEMBER_ID))
                .willReturn(Optional.of(new Member("user@slotkey.test", "회원1")));
        given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.of(
                Space.builder().name("공간1").build()));
    }

    // ---------- getReservationDetail ----------

    @Test
    @DisplayName("존재하지 않는 예약을 상세 조회하면 RESERVATION_NOT_FOUND 예외가 발생한다")
    void getReservationDetail_reservationNotFound_throwsException() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> adminReservationService.getReservationDetail(RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("예약 상세 조회 시 소유자 검사 없이 출입 로그를 조회해 상태 이력과 함께 응답에 담는다")
    void getReservationDetail_success_mapsStatusHistoryAndAccessLogs() {
        Reservation reservation = confirmedReservation();
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation));
        stubMemberAndSpace();

        ReservationStatusHistory history = ReservationStatusHistory.of(
                RESERVATION_ID, MEMBER_ID, null, ReservationStatus.CONFIRMED, null, startTime.minusHours(1));
        given(statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(RESERVATION_ID))
                .willReturn(List.of(history));

        DoorAccessLog allowLog = new DoorAccessLog(
                null, null, null, AccessResult.ALLOW, null, startTime.plusMinutes(1));
        DoorAccessLog denyLog = new DoorAccessLog(
                null, null, null, AccessResult.DENY, AccessDenyReason.TOKEN_REVOKED, startTime.plusMinutes(2));
        given(doorAccessLogService.findAllByReservationId(RESERVATION_ID))
                .willReturn(List.of(allowLog, denyLog));

        AdminReservationDetailResponse response = adminReservationService.getReservationDetail(RESERVATION_ID);

        // 관리자는 예약 소유자가 아니어도 조회할 수 있어야 하므로, owner 검사가 있는
        // findResponsesByReservationId가 아니라 findAllByReservationId가 호출되어야 한다.
        verify(doorAccessLogService).findAllByReservationId(RESERVATION_ID);
        verify(doorAccessLogService, never()).findResponsesByReservationId(any(), any());

        assertThat(response.statusHistory()).hasSize(1);
        assertThat(response.accessLogs()).hasSize(2);
        assertThat(response.accessLogs().get(0).result()).isEqualTo(AccessResult.ALLOW);
        assertThat(response.accessLogs().get(1).result()).isEqualTo(AccessResult.DENY);
        assertThat(response.accessLogs().get(1).reasonCode()).isEqualTo(AccessDenyReason.TOKEN_REVOKED);
    }

    // ---------- forceCancel ----------

    @Test
    @DisplayName("존재하지 않는 예약을 강제 취소하면 RESERVATION_NOT_FOUND 예외가 발생한다")
    void forceCancel_reservationNotFound_throwsException() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("이미 종료 상태(COMPLETED 등)인 예약은 강제 취소할 수 없고 환불을 포함한 후속 처리도 일어나지 않는다")
    void forceCancel_terminalState_throwsExceptionWithoutSideEffects() {
        Reservation completed = reservationWithStatus(ReservationStatus.COMPLETED, 10000);
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(completed));

        assertThatThrownBy(() -> adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT);

        verify(reservationRepository, never()).forceCancelIfStatusIs(any(), any(), any(), any());
        verify(reservationSlotRepository, never()).deleteByReservationId(any());
        verify(doorAccessTokenService, never()).revokeByReservation(any(), any(), any());
        verify(creditService, never()).refund(any(), any(), anyInt());
        verify(statusHistoryRepository, never()).save(any());
        verify(auditLogService, never()).log(any(), any(), any(), any(), any(), any(), any());
        verify(slotReleasedRecorder, never()).record(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("CONFIRMED 예약을 강제 취소하면 CANCELLED로 전이하고 슬롯 반환·토큰 revoke·이력·전액 환불·감사 로그를 모두 수행한다")
    void forceCancel_confirmed_cancelsAndRefundsFullAmount() {
        Reservation confirmed = confirmedReservation();
        Reservation cancelled = reservationWithStatus(ReservationStatus.CANCELLED, 10000);
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(confirmed), Optional.of(cancelled));
        given(reservationRepository.forceCancelIfStatusIs(
                RESERVATION_ID, now, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED)).willReturn(1);
        stubMemberAndSpace();

        AdminReservationResponse response =
                adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID);

        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);

        // 결제된 예약 취소 시 환불 전 회원 배타 잠금 선점 검증 및 Space 배타 잠금 미선점 검증
        verify(spaceRepository, never()).findByIdForUpdate(any());
        verify(memberRepository).findByIdForUpdate(MEMBER_ID);
        verify(reservationSlotRepository).deleteByReservationId(RESERVATION_ID);
        verify(doorAccessTokenService).revokeByReservation(eq(RESERVATION_ID), eq(now), eq("ADMIN_FORCE_CANCEL"));

        // 관리자 사유의 취소이므로 위약금 없이 결제액 전액을 돌려준다.
        verify(creditService).refund(MEMBER_ID, RESERVATION_ID, 10000);
        verify(creditService, never()).penalize(any(), any(), anyInt());

        verify(statusHistoryRepository).save(argThat(history ->
                history.getReservationId().equals(RESERVATION_ID)
                        && history.getChangedByMemberId().equals(ADMIN_MEMBER_ID)
                        && history.getFromStatus() == ReservationStatus.CONFIRMED
                        && history.getToStatus() == ReservationStatus.CANCELLED
                        && history.getReason().equals(REASON)));

        verify(auditLogService).log(
                eq(ADMIN_MEMBER_ID),
                eq(AuditAction.FORCE_CANCEL_RESERVATION),
                eq(AuditTargetType.RESERVATION),
                eq(RESERVATION_ID),
                eq(REASON),
                eq(ReservationStatus.CONFIRMED),
                eq(ReservationStatus.CANCELLED));
        verify(slotReleasedRecorder).record(RESERVATION_ID, SPACE_ID, SlotReleasedRecorder.Reason.FORCE_CANCELLED);
    }

    @Test
    @DisplayName("연장으로 늘어난 총액을 가진 예약을 강제 취소하면 연장분까지 포함한 total_amount 전액을 환불한다")
    void forceCancel_extendedReservation_refundsTotalAmountIncludingExtension() {
        Reservation extended = reservationWithStatus(ReservationStatus.CONFIRMED, 25000);
        Reservation cancelled = reservationWithStatus(ReservationStatus.CANCELLED, 25000);
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(extended), Optional.of(cancelled));
        given(reservationRepository.forceCancelIfStatusIs(
                RESERVATION_ID, now, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED)).willReturn(1);
        stubMemberAndSpace();

        adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID);

        verify(creditService).refund(MEMBER_ID, RESERVATION_ID, 25000);
    }

    @Test
    @DisplayName("이미 이용 중(IN_USE)인 예약을 강제 취소해도 전액 환불한다")
    void forceCancel_inUse_refundsFullAmount() {
        Reservation inUse = reservationWithStatus(ReservationStatus.IN_USE, 10000);
        Reservation cancelled = reservationWithStatus(ReservationStatus.CANCELLED, 10000);
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(inUse), Optional.of(cancelled));
        given(reservationRepository.forceCancelIfStatusIs(
                RESERVATION_ID, now, ReservationStatus.IN_USE, ReservationStatus.CANCELLED)).willReturn(1);
        stubMemberAndSpace();

        adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID);

        verify(spaceRepository, never()).findByIdForUpdate(any());
        verify(memberRepository).findByIdForUpdate(MEMBER_ID);
        verify(creditService).refund(MEMBER_ID, RESERVATION_ID, 10000);
        verify(statusHistoryRepository).save(argThat(history ->
                history.getFromStatus() == ReservationStatus.IN_USE
                        && history.getToStatus() == ReservationStatus.CANCELLED));
        verify(slotReleasedRecorder).record(RESERVATION_ID, SPACE_ID, SlotReleasedRecorder.Reason.FORCE_CANCELLED);
    }

    @Test
    @DisplayName("결제 전(HELD) 예약을 강제 취소하면 Space 배타 잠금을 선점하고 슬롯만 반환하며 환불 및 회원 잠금은 하지 않는다")
    void forceCancel_held_doesNotRefund() {
        Reservation held = reservationWithStatus(ReservationStatus.HELD, 10000);
        Reservation cancelled = reservationWithStatus(ReservationStatus.CANCELLED, 10000);
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(held), Optional.of(cancelled));
        given(spaceRepository.findByIdForUpdate(SPACE_ID))
                .willReturn(Optional.of(Space.builder().id(SPACE_ID).name("공간1").build()));
        given(reservationRepository.forceCancelIfStatusIs(
                RESERVATION_ID, now, ReservationStatus.HELD, ReservationStatus.CANCELLED)).willReturn(1);
        stubMemberAndSpace();

        adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID);

        verify(spaceRepository).findByIdForUpdate(SPACE_ID);
        verify(memberRepository, never()).findByIdForUpdate(any());
        verify(reservationSlotRepository).deleteByReservationId(RESERVATION_ID);
        verify(creditService, never()).refund(any(), any(), anyInt());
        verify(creditService, never()).penalize(any(), any(), anyInt());
        verify(slotReleasedRecorder).record(RESERVATION_ID, SPACE_ID, SlotReleasedRecorder.Reason.FORCE_CANCELLED);
    }

    @Test
    @DisplayName("조회 뒤 다른 요청이 먼저 상태를 바꿔 조건부 UPDATE가 0행이면 RESERVATION_STATE_CONFLICT가 발생하고 환불을 포함한 후속 처리가 일어나지 않는다")
    void forceCancel_statusChangedConcurrently_throwsConflictWithoutSideEffects() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(confirmedReservation()));
        given(reservationRepository.forceCancelIfStatusIs(
                RESERVATION_ID, now, ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED)).willReturn(0);

        assertThatThrownBy(() -> adminReservationService.forceCancel(RESERVATION_ID, REASON, ADMIN_MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT);

        verify(creditService, never()).refund(any(), any(), anyInt());
        verify(reservationSlotRepository, never()).deleteByReservationId(any());
        verify(doorAccessTokenService, never()).revokeByReservation(any(), any(), any());
        verify(statusHistoryRepository, never()).save(any());
        verify(auditLogService, never()).log(any(), any(), any(), any(), any(), any(), any());
        verify(slotReleasedRecorder, never()).record(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("searchReservations: 조건이 비어있으면 null 파라미터와 기본 id DESC 정렬로 리포지토리를 호출한다")
    void searchReservations_defaultCondition_appliesDefaultSortAndMapsResponses() {
        // given
        Reservation reservation = confirmedReservation();
        Page<Reservation> page = new PageImpl<>(List.of(reservation), PageRequest.of(0, 20), 1);

        given(reservationRepository.searchReservations(eq(null), eq(null), eq(null), eq(null), any(Pageable.class)))
                .willReturn(page);
        stubMemberAndSpace();

        // when
        Page<AdminReservationResponse> result = adminReservationService.searchReservations(
                new AdminReservationSearchCondition(null, null, null),
                PageRequest.of(0, 20)
        );

        // then
        assertThat(result.getContent()).hasSize(1);
        AdminReservationResponse item = result.getContent().get(0);
        assertThat(item.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(item.memberEmail()).isEqualTo("user@slotkey.test");
        assertThat(item.spaceName()).isEqualTo("공간1");

        org.mockito.ArgumentCaptor<Pageable> pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(reservationRepository).searchReservations(eq(null), eq(null), eq(null), eq(null), pageableCaptor.capture());
        Pageable capturedPageable = pageableCaptor.getValue();
        assertThat(capturedPageable.getSort().getOrderFor("id")).isNotNull();
        assertThat(capturedPageable.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("searchReservations: date, spaceId, status가 주어지면 [date 00:00, date+1 00:00) 범위와 파라미터를 정확히 전달한다")
    void searchReservations_withAllFilters_passesCorrectArguments() {
        // given
        LocalDate searchDate = LocalDate.of(2026, 9, 30);
        LocalDateTime expectedInclusive = LocalDateTime.of(2026, 9, 30, 0, 0, 0);
        LocalDateTime expectedExclusive = LocalDateTime.of(2026, 10, 1, 0, 0, 0);

        Page<Reservation> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        given(reservationRepository.searchReservations(
                eq(SPACE_ID),
                eq(ReservationStatus.CONFIRMED),
                eq(expectedInclusive),
                eq(expectedExclusive),
                any(Pageable.class)
        )).willReturn(emptyPage);

        // when
        AdminReservationSearchCondition condition = new AdminReservationSearchCondition(
                searchDate, SPACE_ID, ReservationStatus.CONFIRMED
        );
        Page<AdminReservationResponse> result = adminReservationService.searchReservations(condition, PageRequest.of(0, 10));

        // then
        assertThat(result.getContent()).isEmpty();
        verify(reservationRepository).searchReservations(
                eq(SPACE_ID),
                eq(ReservationStatus.CONFIRMED),
                eq(expectedInclusive),
                eq(expectedExclusive),
                any(Pageable.class)
        );
    }

    @Test
    @DisplayName("searchReservations: 클라이언트가 지정한 정렬이 있으면 기본 정렬로 덮어쓰지 않고 유지한다")
    void searchReservations_withClientSort_preservesSort() {
        // given
        Page<Reservation> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        org.mockito.ArgumentCaptor<Pageable> pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        given(reservationRepository.searchReservations(any(), any(), any(), any(), pageableCaptor.capture()))
                .willReturn(emptyPage);

        Pageable clientPageable = PageRequest.of(1, 10, Sort.by(Sort.Direction.ASC, "startTime"));

        // when
        adminReservationService.searchReservations(new AdminReservationSearchCondition(null, null, null), clientPageable);

        // then
        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isEqualTo(1);
        assertThat(captured.getPageSize()).isEqualTo(10);
        assertThat(captured.getSort().getOrderFor("startTime")).isNotNull();
        assertThat(captured.getSort().getOrderFor("startTime").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("findAllReservations: searchReservations를 빈 조건으로 위임 호출한다")
    void findAllReservations_delegatesToSearchReservations() {
        // given
        Reservation reservation = confirmedReservation();
        Page<Reservation> page = new PageImpl<>(List.of(reservation), PageRequest.of(0, 20), 1);
        given(reservationRepository.searchReservations(eq(null), eq(null), eq(null), eq(null), any(Pageable.class)))
                .willReturn(page);
        stubMemberAndSpace();

        // when
        Page<AdminReservationResponse> result = adminReservationService.findAllReservations(PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).hasSize(1);
        verify(reservationRepository).searchReservations(eq(null), eq(null), eq(null), eq(null), any(Pageable.class));
    }
}
