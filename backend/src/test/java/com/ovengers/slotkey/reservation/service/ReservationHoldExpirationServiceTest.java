package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.outbox.SlotReleasedRecorder;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservationHoldExpirationServiceTest {

        private static final Long RESERVATION_ID = 100L;
        private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 14, 0, 0);

        @Mock
        private ReservationRepository reservationRepository;

        @Mock
        private ReservationSlotRepository reservationSlotRepository;

        @Mock
        private ReservationStatusHistoryRepository reservationStatusHistoryRepository;

        private ReservationHoldExpirationService expirationService;
        @Mock
        private SlotReleasedRecorder slotReleasedRecorder;

        @BeforeEach
        void setUp() {
                expirationService = new ReservationHoldExpirationService(
                                reservationRepository,
                                reservationSlotRepository,
                                reservationStatusHistoryRepository, slotReleasedRecorder);
                org.mockito.Mockito.lenient().when(reservationRepository.findById(any()))
                                .thenReturn(Optional.of(Reservation.builder().spaceId(5L).build()));
        }

        @Test
        @DisplayName("조건부 UPDATE가 1이면 슬롯을 삭제하고 HOLD_EXPIRED 이력을 저장한 뒤 true를 반환한다")
        void expireHold_whenUpdateSucceeds_deletesSlotsAndSavesHistoryAndReturnsTrue() {
                // given
                given(reservationRepository.expireHeldReservations(
                                List.of(RESERVATION_ID),
                                NOW,
                                ReservationStatus.HELD,
                                ReservationStatus.EXPIRED)).willReturn(1);

                // when
                boolean result = expirationService.expireHold(RESERVATION_ID, NOW);

                // then
                assertThat(result).isTrue();

                verify(reservationRepository).expireHeldReservations(
                                List.of(RESERVATION_ID),
                                NOW,
                                ReservationStatus.HELD,
                                ReservationStatus.EXPIRED);

                verify(reservationSlotRepository).deleteSlotsOfExpiredReservations(
                                List.of(RESERVATION_ID),
                                ReservationStatus.EXPIRED);

                verify(reservationStatusHistoryRepository)
                                .save(argThat(history -> history.getReservationId().equals(RESERVATION_ID)
                                                && history.getChangedByMemberId() == null
                                                && history.getFromStatus() == ReservationStatus.HELD
                                                && history.getToStatus() == ReservationStatus.EXPIRED
                                                && "HOLD_EXPIRED".equals(history.getReason())
                                                && history.getChangedAt().equals(NOW)));

                verify(slotReleasedRecorder).record(RESERVATION_ID, 5L, SlotReleasedRecorder.Reason.HOLD_EXPIRED);
        }

        @Test
        @DisplayName("조건부 UPDATE가 0이면 방어적 슬롯 정리 쿼리는 실행하되 이력 저장은 수행하지 않고 false를 반환한다")
        void expireHold_whenUpdateFails_cleansUpSlotsDefensivelyWithoutSavingHistoryAndReturnsFalse() {
                // given
                given(reservationRepository.expireHeldReservations(
                                List.of(RESERVATION_ID),
                                NOW,
                                ReservationStatus.HELD,
                                ReservationStatus.EXPIRED)).willReturn(0);

                // when
                boolean result = expirationService.expireHold(RESERVATION_ID, NOW);

                // then
                assertThat(result).isFalse();

                verify(reservationRepository).expireHeldReservations(
                                List.of(RESERVATION_ID),
                                NOW,
                                ReservationStatus.HELD,
                                ReservationStatus.EXPIRED);

                verify(reservationSlotRepository).deleteSlotsOfExpiredReservations(
                                List.of(RESERVATION_ID),
                                ReservationStatus.EXPIRED);

                verify(reservationStatusHistoryRepository, never()).save(any());
                verify(slotReleasedRecorder, never()).record(
                                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
        }

        @Test
        @DisplayName("모든 저장소 호출에 호출자가 전달한 동일한 reservationId와 now가 전달된다")
        void expireHold_passesSameParametersAcrossAllCalls() {
                // given
                Long targetId = 200L;
                LocalDateTime testNow = LocalDateTime.of(2026, 9, 20, 15, 30, 0);

                given(reservationRepository.expireHeldReservations(
                                List.of(targetId),
                                testNow,
                                ReservationStatus.HELD,
                                ReservationStatus.EXPIRED)).willReturn(1);

                // when
                expirationService.expireHold(targetId, testNow);

                // then
                verify(reservationRepository).expireHeldReservations(
                                List.of(targetId),
                                testNow,
                                ReservationStatus.HELD,
                                ReservationStatus.EXPIRED);

                verify(reservationSlotRepository).deleteSlotsOfExpiredReservations(
                                List.of(targetId),
                                ReservationStatus.EXPIRED);

                verify(reservationStatusHistoryRepository).save(argThat(
                                h -> h.getReservationId().equals(targetId) && h.getChangedAt().equals(testNow)));
        }
}
