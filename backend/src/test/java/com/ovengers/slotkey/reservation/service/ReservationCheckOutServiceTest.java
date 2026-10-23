package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.access.service.DoorAccessTokenService;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class ReservationCheckOutServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long OTHER_MEMBER_ID = 2L;
    private static final Long RESERVATION_ID = 10L;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 13, 30);
    private static final LocalDateTime START_TIME = LocalDateTime.of(2026, 9, 17, 13, 0);
    private static final LocalDateTime END_TIME = LocalDateTime.of(2026, 9, 17, 14, 0);

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationStatusHistoryRepository reservationStatusHistoryRepository;

    @Mock
    private DoorAccessTokenService doorAccessTokenService;

    private ReservationCheckOutService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

        service = new ReservationCheckOutService(
                reservationRepository,
                reservationStatusHistoryRepository,
                clock,
                doorAccessTokenService
        );
    }

    @Test
    @DisplayName("존재하지 않는 예약이면 체크아웃할 수 없다")
    void shouldRejectCheckOutWhenReservationDoesNotExist() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkOut(
                MEMBER_ID,
                RESERVATION_ID
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND)
        );

        verify(doorAccessTokenService, never()).revokeByReservation(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("예약 소유자가 아니면 체크아웃할 수 없다")
    void shouldRejectCheckOutWhenMemberIsNotOwner() {
        Reservation reservation = createReservation(
                MEMBER_ID,
                ReservationStatus.IN_USE,
                null
        );

        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.checkOut(
                OTHER_MEMBER_ID,
                RESERVATION_ID
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN_NOT_OWNER)
        );

        verify(reservationRepository, never()).checkOutIfInUse(
                any(),
                any(),
                any(),
                any()
        );

        verify(doorAccessTokenService, never()).revokeByReservation(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("예약 상태 전이에 실패하면 출입 토큰을 폐기하지 않는다")
    void shouldNotRevokeTokenWhenCheckOutTransitionFails() {
        Reservation reservation = createReservation(
                MEMBER_ID,
                ReservationStatus.IN_USE,
                null
        );

        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation));

        given(reservationRepository.checkOutIfInUse(
                RESERVATION_ID,
                NOW,
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED
        )).willReturn(0);

        assertThatThrownBy(() -> service.checkOut(
                MEMBER_ID,
                RESERVATION_ID
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_STATE_CONFLICT)
        );

        verify(doorAccessTokenService, never()).revokeByReservation(
                any(),
                any(),
                any()
        );

        verify(reservationStatusHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("체크아웃에 성공하면 출입 토큰을 폐기하고 상태 이력을 저장한다")
    void shouldRevokeTokenAndSaveHistoryWhenCheckOutSucceeds() {
        Reservation inUseReservation = createReservation(
                MEMBER_ID,
                ReservationStatus.IN_USE,
                null
        );

        Reservation completedReservation = createReservation(
                MEMBER_ID,
                ReservationStatus.COMPLETED,
                NOW
        );

        given(reservationRepository.findById(RESERVATION_ID)).willReturn(
                Optional.of(inUseReservation),
                Optional.of(completedReservation)
        );

        given(reservationRepository.checkOutIfInUse(
                RESERVATION_ID,
                NOW,
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED
        )).willReturn(1);

        ReservationResponse response = service.checkOut(
                MEMBER_ID,
                RESERVATION_ID
        );

        verify(doorAccessTokenService).revokeByReservation(
                RESERVATION_ID,
                NOW,
                "CHECKED_OUT"
        );

        verify(reservationStatusHistoryRepository).save(argThat(history ->
                history.getReservationId().equals(RESERVATION_ID)
                        && history.getChangedByMemberId().equals(MEMBER_ID)
                        && history.getFromStatus() == ReservationStatus.IN_USE
                        && history.getToStatus() == ReservationStatus.COMPLETED
        ));

        assertThat(response.status()).isEqualTo("COMPLETED");

        assertThat(response.checkedOutAt()).isEqualTo(NOW);
    }

    private Reservation createReservation(
            Long memberId,
            ReservationStatus status,
            LocalDateTime checkedOutAt
    ) {
        return Reservation.builder()
                .id(RESERVATION_ID)
                .memberId(memberId)
                .spaceId(5L)
                .startTime(START_TIME)
                .endTime(END_TIME)
                .status(status)
                .pricePerSlotSnapshot(5000)
                .totalAmount(20000)
                .checkedInAt(START_TIME)
                .checkedOutAt(checkedOutAt)
                .createdAt(START_TIME.minusDays(1))
                .build();
    }
}
