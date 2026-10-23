package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.credit.service.CreditService;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.global.idempotency.IdempotencyService;
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ReservationPaymentConfirmService(결제 확인의 멱등성·소유권 검증)에 대한 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ReservationPaymentConfirmServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String REQUEST_PATH = "/reservations/10/pay";
    private static final Long MEMBER_ID = 1L;
    private static final Long OTHER_MEMBER_ID = 2L;
    private static final Long RESERVATION_ID = 10L;
    private static final Long SPACE_ID = 5L;

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationStatusHistoryRepository reservationStatusHistoryRepository;
    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private CreditService creditService;
    @Mock
    private IdempotencyService idempotencyService;

    private Clock clock;
    private ReservationPaymentConfirmService confirmService;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.of(2026, 9, 17, 13, 0);
        clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
        confirmService = new ReservationPaymentConfirmService(
                reservationRepository, reservationStatusHistoryRepository,
                spaceRepository, creditService, idempotencyService, clock);
    }

    private Reservation heldReservation(Long memberId) {
        return Reservation.builder()
                .id(RESERVATION_ID)
                .memberId(memberId)
                .spaceId(SPACE_ID)
                .startTime(now.plusHours(1))
                .endTime(now.plusHours(2))
                .status(ReservationStatus.HELD)
                .pricePerSlotSnapshot(5000)
                .totalAmount(10000)
                .holdExpiresAt(now.plusMinutes(5))
                .createdAt(now.minusMinutes(5))
                .build();
    }

    private Reservation confirmedReservation() {
        return Reservation.builder()
                .id(RESERVATION_ID)
                .memberId(MEMBER_ID)
                .spaceId(SPACE_ID)
                .startTime(now.plusHours(1))
                .endTime(now.plusHours(2))
                .status(ReservationStatus.CONFIRMED)
                .pricePerSlotSnapshot(5000)
                .totalAmount(10000)
                .holdExpiresAt(now.plusMinutes(5))
                .createdAt(now.minusMinutes(5))
                .build();
    }

    private Space spaceWithVersion(int version) {
        return Space.builder()
                .id(SPACE_ID)
                .name("컨퍼런스 룸")
                .location("서울")
                .capacity(10)
                .pricePerSlot(5000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(22, 0))
                .status(SpaceStatus.ACTIVE)
                .version(version)
                .build();
    }

    @Test
    @DisplayName("Idempotency-Key가 없거나 공백이면 IDEMPOTENCY_KEY_REQUIRED 예외가 발생하고 다른 처리는 하지 않는다")
    void confirm_blankKey_throwsException() {
        assertThatThrownBy(() -> confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, " "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_REQUIRED);

        verifyNoInteractions(idempotencyService, reservationRepository, spaceRepository,
                creditService, reservationStatusHistoryRepository);
    }

    @Test
    @DisplayName("이미 처리된 키로 재요청하면 캐시된 응답을 그대로 반환하고 크레딧을 다시 차감하지 않는다")
    void confirm_cachedKey_returnsCachedResponseWithoutCharging() {
        ReservationResponse cached = ReservationResponse.from(confirmedReservation());
        given(idempotencyService.find("key-1", MEMBER_ID, REQUEST_PATH, ReservationResponse.class))
                .willReturn(cached);

        ReservationResponse response = confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, "key-1");

        assertThat(response).isEqualTo(cached);
        verifyNoInteractions(reservationRepository, creditService, spaceRepository, reservationStatusHistoryRepository);
        verify(idempotencyService, never()).save(any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("본인이 아닌 예약을 확인 요청하면 공간 버전이 정확하더라도 FORBIDDEN_NOT_OWNER 예외가 발생하고 Space 잠금을 요청하지 않는다")
    void confirm_notOwner_correctVersion_throwsForbidden_withoutSpaceLock() {
        given(idempotencyService.find("key-1", OTHER_MEMBER_ID, REQUEST_PATH, ReservationResponse.class))
                .willReturn(null);
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
                        .willReturn(Optional.of(new com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(SPACE_ID,
                                        MEMBER_ID)));

        assertThatThrownBy(() -> confirmService.confirm(OTHER_MEMBER_ID, RESERVATION_ID, 0, "key-1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER);

        verifyNoInteractions(spaceRepository, creditService);
}

@Test
@DisplayName("본인이 아닌 예약을 확인 요청하면 잘못된 공간 버전을 보내더라도 FORBIDDEN_NOT_OWNER가 발생하고 Space 잠금을 요청하지 않는다")
void confirm_notOwner_wrongVersion_throwsForbidden_withoutSpaceLock() {
        given(idempotencyService.find("key-1", OTHER_MEMBER_ID, REQUEST_PATH, ReservationResponse.class))
                        .willReturn(null);
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
                        .willReturn(Optional.of(new com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(SPACE_ID,
                                        MEMBER_ID)));

        assertThatThrownBy(() -> confirmService.confirm(OTHER_MEMBER_ID, RESERVATION_ID, 999, "key-1"))
                        .isInstanceOf(BusinessException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER);

        verifyNoInteractions(spaceRepository, creditService);
    }

    @Test
    @DisplayName("결제 대기 중 공간 가격 버전이 바뀌었으면 SPACE_VERSION_MISMATCH 예외가 발생한다")
    void confirm_spaceVersionMismatch_throwsException() {
        given(idempotencyService.find("key-1", MEMBER_ID, REQUEST_PATH, ReservationResponse.class))
                .willReturn(null);
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
                        .willReturn(Optional.of(new com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(SPACE_ID,
                                        MEMBER_ID)));
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(spaceWithVersion(1)));

        assertThatThrownBy(() -> confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, "key-1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_VERSION_MISMATCH);

        verifyNoInteractions(creditService);
    }

    @Test
    @DisplayName("크레딧 잔액이 부족하면 예외가 전파되고 예약 상태 전이는 시도하지 않는다")
    void confirm_insufficientBalance_propagatesExceptionWithoutTransition() {
        given(idempotencyService.find("key-1", MEMBER_ID, REQUEST_PATH, ReservationResponse.class))
                .willReturn(null);
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
                        .willReturn(Optional.of(new com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(SPACE_ID,
                                        MEMBER_ID)));
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(spaceWithVersion(0)));
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(heldReservation(MEMBER_ID)));
        given(creditService.charge(MEMBER_ID, RESERVATION_ID, 10000))
                .willThrow(new BusinessException(ErrorCode.INSUFFICIENT_BALANCE));

        assertThatThrownBy(() -> confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, "key-1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_BALANCE);

        verify(reservationRepository, never()).confirmIfHeldAndNotExpired(any(), any(), any(), any());
        verifyNoInteractions(reservationStatusHistoryRepository);
        verify(idempotencyService, never()).save(any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("결제 확인 시점에 HOLD가 이미 만료되었으면 RESERVATION_STATE_CONFLICT 예외가 발생한다")
    void confirm_holdExpired_throwsException() {
        given(idempotencyService.find("key-1", MEMBER_ID, REQUEST_PATH, ReservationResponse.class))
                .willReturn(null);
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
                        .willReturn(Optional.of(new com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(SPACE_ID,
                                        MEMBER_ID)));
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(spaceWithVersion(0)));
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(heldReservation(MEMBER_ID)));
        given(creditService.charge(MEMBER_ID, RESERVATION_ID, 10000)).willReturn(80000);
        given(reservationRepository.confirmIfHeldAndNotExpired(
                RESERVATION_ID, now, ReservationStatus.HELD, ReservationStatus.CONFIRMED))
                .willReturn(0);

        assertThatThrownBy(() -> confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, "key-1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT);

        verifyNoInteractions(reservationStatusHistoryRepository);
        verify(idempotencyService, never()).save(any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("정상 흐름에서는 크레딧을 차감하고 CONFIRMED로 전이한 뒤 이력과 응답을 저장한다")
    void confirm_success_confirmsReservationAndCachesResponse() {
        Reservation held = heldReservation(MEMBER_ID);
        Reservation confirmed = confirmedReservation();
        given(idempotencyService.find("key-1", MEMBER_ID, REQUEST_PATH, ReservationResponse.class))
                .willReturn(null);
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
                        .willReturn(Optional.of(new com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(SPACE_ID,
                                        MEMBER_ID)));
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(spaceWithVersion(0)));
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(held), Optional.of(confirmed));
        given(creditService.charge(MEMBER_ID, RESERVATION_ID, 10000)).willReturn(90000);
        given(reservationRepository.confirmIfHeldAndNotExpired(
                RESERVATION_ID, now, ReservationStatus.HELD, ReservationStatus.CONFIRMED))
                .willReturn(1);

        ReservationResponse response = confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, "key-1");

        assertThat(response.status()).isEqualTo("CONFIRMED");
        verify(creditService).charge(MEMBER_ID, RESERVATION_ID, 10000);
        verify(reservationStatusHistoryRepository).save(argThat(history ->
                history.getFromStatus() == ReservationStatus.HELD
                        && history.getToStatus() == ReservationStatus.CONFIRMED));
        verify(idempotencyService).save(eq("key-1"), eq(MEMBER_ID), eq(REQUEST_PATH), eq(200), eq(response));
    }
}
