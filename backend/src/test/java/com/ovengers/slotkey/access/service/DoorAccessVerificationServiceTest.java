package com.ovengers.slotkey.access.service;

import com.ovengers.slotkey.access.dto.request.DoorAccessVerifyRequest;
import com.ovengers.slotkey.access.dto.response.DoorAccessVerifyResponse;
import com.ovengers.slotkey.access.entity.AccessDenyReason;
import com.ovengers.slotkey.access.entity.AccessResult;
import com.ovengers.slotkey.access.entity.DoorAccessToken;
import com.ovengers.slotkey.access.policy.DoorAccessTimePolicy;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DoorAccessVerificationServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long OTHER_MEMBER_ID = 2L;
    private static final Long RESERVATION_ID = 3L;
    private static final Long SPACE_ID = 10L;
    private static final Long OTHER_SPACE_ID = 20L;
    private static final String RAW_TOKEN = "raw-token";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 10, 0);
    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 9, 17, 10, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 9, 17, 11, 0);

    @Mock
    private DoorAccessTokenService doorAccessTokenService;

    @Mock
    private DoorAccessLogService doorAccessLogService;

    @Mock
    private DoorAccessTimePolicy doorAccessTimePolicy;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private com.ovengers.slotkey.access.repository.DoorAccessTokenRepository doorAccessTokenRepository;

    @Mock
    private SpaceRepository spaceRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationStatusHistoryRepository reservationStatusHistoryRepository;

    @Mock
    private jakarta.persistence.EntityManager entityManager;

    @Mock
    private DoorAccessVerifyRequest request;

    @Mock
    private DoorAccessToken accessToken;

    @Mock
    private Reservation reservation;

    @Mock
    private Member actorMember;

    @Mock
    private Space requestedSpace;

    private DoorAccessVerificationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

        service = new DoorAccessVerificationService(
                doorAccessTokenService,
                doorAccessTokenRepository,
                doorAccessLogService,
                doorAccessTimePolicy,
                memberRepository,
                spaceRepository,
                clock,
                reservationRepository,
                reservationStatusHistoryRepository,
                entityManager
        );
    }

    @Test
    @DisplayName("존재하지 않는 출입 토큰이면 출입을 거절한다")
    void shouldDenyWhenTokenDoesNotExist() {
        givenRequestContext();

        given(doorAccessTokenService.findByRawTokenOrNull(RAW_TOKEN))
                        .willReturn(null);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertDenied(response, AccessDenyReason.TOKEN_NOT_FOUND);

        verify(doorAccessLogService).createDenyLog(
                actorMember,
                null,
                requestedSpace,
                AccessDenyReason.TOKEN_NOT_FOUND,
                NOW
        );
    }

    @Test
    @DisplayName("로그인 회원과 예약 회원이 다르면 출입을 거절한다")
    void shouldDenyWhenMemberDoesNotMatch() {
        givenRequestContext();
        givenTokenAndReservation();

        given(reservation.getMemberId()).willReturn(OTHER_MEMBER_ID);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertDenied(response, AccessDenyReason.MEMBER_MISMATCH);

        verify(doorAccessLogService).createDenyLog(
                actorMember,
                reservation,
                requestedSpace,
                AccessDenyReason.MEMBER_MISMATCH,
                NOW
        );
    }

    @Test
    @DisplayName("폐기된 출입 토큰이면 출입을 거절한다")
    void shouldDenyWhenTokenIsRevoked() {
        givenRequestContext();
        givenOwnedTokenAndReservation();

        given(accessToken.isRevoked()).willReturn(true);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertDenied(response, AccessDenyReason.TOKEN_REVOKED);

        verify(doorAccessLogService).createDenyLog(
                actorMember,
                reservation,
                requestedSpace,
                AccessDenyReason.TOKEN_REVOKED,
                NOW
        );
    }

    @Test
    @DisplayName("출입 가능한 예약 상태가 아니면 출입을 거절한다")
    void shouldDenyWhenReservationIsNotActive() {
        givenRequestContext();
        givenOwnedTokenAndReservation();

        given(reservation.getStatus()).willReturn(ReservationStatus.HELD);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertDenied(response, AccessDenyReason.RESERVATION_NOT_ACTIVE);

        verify(doorAccessLogService).createDenyLog(
                actorMember,
                reservation,
                requestedSpace,
                AccessDenyReason.RESERVATION_NOT_ACTIVE,
                NOW
        );
    }

    @Test
    @DisplayName("요청 공간과 예약 공간이 다르면 출입을 거절한다")
    void shouldDenyWhenSpaceDoesNotMatch() {
        givenRequestContext();
        givenOwnedTokenAndReservation();

        given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
        given(reservation.getSpaceId()).willReturn(OTHER_SPACE_ID);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertDenied(response, AccessDenyReason.SPACE_MISMATCH);

        verify(doorAccessLogService).createDenyLog(
                actorMember,
                reservation,
                requestedSpace,
                AccessDenyReason.SPACE_MISMATCH,
                NOW
        );
    }

    @Test
    @DisplayName("출입 가능한 시간이 아니면 출입을 거절한다")
    void shouldDenyWhenOutsideAllowedTime() {
        givenRequestContext();
        givenActiveReservation();

        given(doorAccessTimePolicy.findDenyReason(
                NOW,
                START_AT,
                END_AT,
                null
        )).willReturn(AccessDenyReason.OUTSIDE_ALLOWED_TIME);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertDenied(response, AccessDenyReason.OUTSIDE_ALLOWED_TIME);

        verify(doorAccessLogService).createDenyLog(
                actorMember,
                reservation,
                requestedSpace,
                AccessDenyReason.OUTSIDE_ALLOWED_TIME,
                NOW
        );
    }

    @Test
    @DisplayName("모든 출입 조건을 만족하면 출입을 허용하고 최초 체크인한다")
    void shouldAllowAccessAndCheckIn() {
        givenRequestContext();
        givenActiveReservation();
        given(reservation.getId()).willReturn(RESERVATION_ID);
        given(accessToken.getId()).willReturn(100L);

        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(reservation));
        given(doorAccessTokenRepository.findByIdForUpdate(100L)).willReturn(Optional.of(accessToken));
        given(accessToken.isRevoked()).willReturn(false);

        given(reservationRepository.checkInIfConfirmed(
                RESERVATION_ID,
                NOW,
                ReservationStatus.CONFIRMED,
                ReservationStatus.IN_USE
        )).willReturn(1);
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation));

        given(doorAccessTimePolicy.findDenyReason(
                NOW,
                START_AT,
                END_AT,
                null
        )).willReturn(null);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertThat(response.getResult()).isEqualTo(AccessResult.ALLOW);
        assertThat(response.getReasonCode()).isNull();
        assertThat(response.getAttemptedAt()).isEqualTo(NOW);
        verify(entityManager).detach(reservation);
        verify(entityManager).detach(accessToken);

        verify(reservationRepository).checkInIfConfirmed(
                RESERVATION_ID,
                NOW,
                ReservationStatus.CONFIRMED,
                ReservationStatus.IN_USE
        );

        verify(reservationStatusHistoryRepository)
                        .save(argThat(history -> RESERVATION_ID.equals(history.getReservationId())
                                        && MEMBER_ID.equals(history.getChangedByMemberId())
                                        && history.getFromStatus() == ReservationStatus.CONFIRMED
                                        && history.getToStatus() == ReservationStatus.IN_USE
                                        && "FIRST_CHECK_IN".equals(history.getReason())
                                        && NOW.equals(history.getChangedAt())));

        verify(doorAccessLogService).createAllowLog(
                actorMember,
                reservation,
                requestedSpace,
                NOW
        );
    }

    @Test
    @DisplayName("최초 체크인 직전 locking read 시점에 동시 강제 취소 등으로 상태가 이미 비활성화되었으면 출입을 거절한다")
    void shouldDenyWhenFirstCheckInFailsDueToConcurrentConflict() {
        givenRequestContext();
        givenActiveReservation();
        given(reservation.getId()).willReturn(RESERVATION_ID);

        Reservation cancelledReservation = org.mockito.Mockito.mock(Reservation.class);
        given(cancelledReservation.getStatus()).willReturn(ReservationStatus.CANCELLED);
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(cancelledReservation));

        given(doorAccessTimePolicy.findDenyReason(
                NOW,
                START_AT,
                END_AT,
                null
        )).willReturn(null);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertDenied(response, AccessDenyReason.RESERVATION_NOT_ACTIVE);

        verify(entityManager).detach(reservation);
        verify(entityManager).detach(accessToken);

        verify(reservationRepository, org.mockito.Mockito.never()).checkInIfConfirmed(any(), any(), any(), any());
        verify(reservationStatusHistoryRepository, org.mockito.Mockito.never()).save(any());
        verify(doorAccessLogService).createDenyLog(
                actorMember,
                cancelledReservation,
                requestedSpace,
                AccessDenyReason.RESERVATION_NOT_ACTIVE,
                NOW
        );
    }

    @Test
    @DisplayName("최종 허가 전 예약 locking read 시 예약이 사라진 경우 출입을 거절하고 fail-closed 처리한다")
    void shouldDenyWhenReservationDisappearsAfterCheckInUpdate() {
        givenRequestContext();
        givenActiveReservation();
        given(reservation.getId()).willReturn(RESERVATION_ID);

        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.empty());

        given(doorAccessTimePolicy.findDenyReason(
                NOW,
                START_AT,
                END_AT,
                null
        )).willReturn(null);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertDenied(response, AccessDenyReason.RESERVATION_NOT_ACTIVE);

        verify(entityManager).detach(reservation);
        verify(entityManager).detach(accessToken);

        verify(reservationRepository, org.mockito.Mockito.never()).checkInIfConfirmed(any(), any(), any(), any());
        verify(doorAccessLogService).createDenyLog(
                actorMember,
                null,
                requestedSpace,
                AccessDenyReason.RESERVATION_NOT_ACTIVE,
                NOW
        );
    }

    @Test
    @DisplayName("최종 허가 전 locking read 시 최신 예약이 활성이지만 토큰이 폐기된 경우 TOKEN_REVOKED로 거절한다")
    void shouldDenyWithTokenRevokedWhenReservationInUseButTokenRevokedAfterCheckInUpdate() {
        givenRequestContext();
        givenActiveReservation();
        given(reservation.getId()).willReturn(RESERVATION_ID);
        given(accessToken.getId()).willReturn(100L);

        Reservation inUseReservation = org.mockito.Mockito.mock(Reservation.class);
        given(inUseReservation.getStatus()).willReturn(ReservationStatus.IN_USE);
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(inUseReservation));

        DoorAccessToken revokedToken = org.mockito.Mockito.mock(DoorAccessToken.class);
        given(revokedToken.isRevoked()).willReturn(true);
        given(doorAccessTokenRepository.findByIdForUpdate(100L)).willReturn(Optional.of(revokedToken));

        given(doorAccessTimePolicy.findDenyReason(
                NOW,
                START_AT,
                END_AT,
                null
        )).willReturn(null);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertDenied(response, AccessDenyReason.TOKEN_REVOKED);

        verify(entityManager).detach(reservation);
        verify(entityManager).detach(accessToken);

        verify(reservationRepository, org.mockito.Mockito.never()).checkInIfConfirmed(any(), any(), any(), any());
        verify(reservationStatusHistoryRepository, org.mockito.Mockito.never()).save(any());
        verify(doorAccessLogService).createDenyLog(
                actorMember,
                inUseReservation,
                requestedSpace,
                AccessDenyReason.TOKEN_REVOKED,
                NOW
        );
    }

    @Test
    @DisplayName("최신 예약이 IN_USE이고 토큰이 활성이면 재입장으로 출입을 허용한다")
    void shouldAllowWhenAlreadyCheckedInByConcurrentRequestAndTokenActive() {
        givenRequestContext();
        givenActiveReservation();
        given(reservation.getId()).willReturn(RESERVATION_ID);
        given(accessToken.getId()).willReturn(100L);

        Reservation inUseReservation = org.mockito.Mockito.mock(Reservation.class);
        given(inUseReservation.getStatus()).willReturn(ReservationStatus.IN_USE);
        given(inUseReservation.getSpaceId()).willReturn(SPACE_ID);
        given(inUseReservation.getStartTime()).willReturn(START_AT);
        given(inUseReservation.getEndTime()).willReturn(END_AT);
        given(inUseReservation.getCheckedInAt()).willReturn(NOW.minusMinutes(10));
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(inUseReservation));

        DoorAccessToken activeToken = org.mockito.Mockito.mock(DoorAccessToken.class);
        given(activeToken.isRevoked()).willReturn(false);
        given(doorAccessTokenRepository.findByIdForUpdate(100L)).willReturn(Optional.of(activeToken));

        given(doorAccessTimePolicy.findDenyReason(
                NOW,
                START_AT,
                END_AT,
                null
        )).willReturn(null);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertThat(response.getResult()).isEqualTo(AccessResult.ALLOW);
        assertThat(response.getReasonCode()).isNull();
        assertThat(response.getFirstCheckIn()).isFalse();

        verify(entityManager).detach(reservation);
        verify(entityManager).detach(accessToken);

        verify(reservationRepository, org.mockito.Mockito.never()).checkInIfConfirmed(any(), any(), any(), any());
        verify(reservationStatusHistoryRepository, org.mockito.Mockito.never()).save(any());
        verify(doorAccessLogService).createAllowLog(
                actorMember,
                inUseReservation,
                requestedSpace,
                NOW
        );
    }

    @Test
    @DisplayName("재입장 시점에 최신 예약이 취소 상태로 변경되어 있으면 출입을 거절한다")
    void shouldDenyReentryWhenReservationCancelledConcurrentlyBeforeLockingRead() {
        givenRequestContext();
        givenActiveReservation();
        given(reservation.getStatus()).willReturn(ReservationStatus.IN_USE);
        given(reservation.getId()).willReturn(RESERVATION_ID);

        Reservation cancelledReservation = org.mockito.Mockito.mock(Reservation.class);
        given(cancelledReservation.getStatus()).willReturn(ReservationStatus.CANCELLED);
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(cancelledReservation));

        given(doorAccessTimePolicy.findDenyReason(
                NOW,
                START_AT,
                END_AT,
                null
        )).willReturn(null);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertDenied(response, AccessDenyReason.RESERVATION_NOT_ACTIVE);

        verify(entityManager).detach(reservation);
        verify(entityManager).detach(accessToken);

        verify(reservationRepository, org.mockito.Mockito.never()).checkInIfConfirmed(any(), any(), any(), any());
        verify(reservationStatusHistoryRepository, org.mockito.Mockito.never()).save(any());
        verify(doorAccessLogService).createDenyLog(
                actorMember,
                cancelledReservation,
                requestedSpace,
                AccessDenyReason.RESERVATION_NOT_ACTIVE,
                NOW
        );
    }

    @Test
    @DisplayName("재입장 시점에 최신 토큰이 폐기 상태로 변경되어 있으면 TOKEN_REVOKED로 거절한다")
    void shouldDenyReentryWhenTokenRevokedConcurrentlyBeforeLockingRead() {
        givenRequestContext();
        givenActiveReservation();
        given(reservation.getStatus()).willReturn(ReservationStatus.IN_USE);
        given(reservation.getId()).willReturn(RESERVATION_ID);
        given(accessToken.getId()).willReturn(100L);

        Reservation inUseReservation = org.mockito.Mockito.mock(Reservation.class);
        given(inUseReservation.getStatus()).willReturn(ReservationStatus.IN_USE);
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(inUseReservation));

        DoorAccessToken revokedToken = org.mockito.Mockito.mock(DoorAccessToken.class);
        given(revokedToken.isRevoked()).willReturn(true);
        given(doorAccessTokenRepository.findByIdForUpdate(100L)).willReturn(Optional.of(revokedToken));

        given(doorAccessTimePolicy.findDenyReason(
                NOW,
                START_AT,
                END_AT,
                null
        )).willReturn(null);

        DoorAccessVerifyResponse response = service.verify(
                MEMBER_ID,
                request
        );

        assertDenied(response, AccessDenyReason.TOKEN_REVOKED);

        verify(entityManager).detach(reservation);
        verify(entityManager).detach(accessToken);

        verify(reservationRepository, org.mockito.Mockito.never()).checkInIfConfirmed(any(), any(), any(), any());
        verify(reservationStatusHistoryRepository, org.mockito.Mockito.never()).save(any());
        verify(doorAccessLogService).createDenyLog(
                actorMember,
                inUseReservation,
                requestedSpace,
                AccessDenyReason.TOKEN_REVOKED,
                NOW
        );
    }

    private void givenRequestContext() {
        given(request.getSpaceId()).willReturn(SPACE_ID);
        given(request.getToken()).willReturn(RAW_TOKEN);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(actorMember));
        given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.of(requestedSpace));
    }

    private void givenTokenAndReservation() {
            given(doorAccessTokenService.findByRawTokenOrNull(RAW_TOKEN))
                            .willReturn(accessToken);
        given(accessToken.getReservation()).willReturn(reservation);
    }

    private void givenOwnedTokenAndReservation() {
        givenTokenAndReservation();

        given(reservation.getMemberId()).willReturn(MEMBER_ID);
    }

    private void givenActiveReservation() {
        givenOwnedTokenAndReservation();

        given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED);
        given(reservation.getSpaceId()).willReturn(SPACE_ID);
        given(reservation.getStartTime()).willReturn(START_AT);
        given(reservation.getEndTime()).willReturn(END_AT);
        given(reservation.getCheckedInAt()).willReturn(null);
    }

    private void assertDenied(
            DoorAccessVerifyResponse response,
            AccessDenyReason reasonCode
    ) {
        assertThat(response.getResult()).isEqualTo(AccessResult.DENY);
        assertThat(response.getReasonCode()).isEqualTo(reasonCode);
        assertThat(response.getAttemptedAt()).isEqualTo(NOW);
    }
}