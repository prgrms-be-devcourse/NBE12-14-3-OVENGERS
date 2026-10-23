package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.credit.service.CreditService;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.entity.MemberStatus;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.policy.ReservationTimePolicy;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReservationExtendServiceTest {

        private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
        private static final Long MEMBER_ID = 1L;
        private static final Long OTHER_MEMBER_ID = 2L;
        private static final Long RESERVATION_ID = 10L;
        private static final Long SPACE_ID = 5L;


        @Mock
        private ReservationRepository reservationRepository;
        @Mock
        private SpaceRepository spaceRepository;
        @Mock
        private MemberRepository memberRepository;
        @Mock
        private ReservationSlotService reservationSlotService;
        @Mock
        private PricingService pricingService;
        @Mock
        private CreditService creditService;

        private Clock clock;
        private ReservationExtendService extendService;
        private LocalDateTime now;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private LocalDateTime newEndTime;

        @BeforeEach
        void setUp() {
                now = LocalDateTime.of(2026, 9, 20, 10, 0);
                clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
                extendService = new ReservationExtendService(
                                reservationRepository, spaceRepository, memberRepository, reservationSlotService, pricingService,
                                creditService, clock);

                startTime = LocalDateTime.of(2026, 9, 20, 11, 0);
                endTime = LocalDateTime.of(2026, 9, 20, 13, 0);
                newEndTime = LocalDateTime.of(2026, 9, 20, 14, 0);
        }

        private Reservation activeReservation(ReservationStatus status) {
                return Reservation.builder()
                                .id(RESERVATION_ID)
                                .memberId(MEMBER_ID)
                                .spaceId(SPACE_ID)
                                .status(status)
                                .startTime(startTime)
                                .endTime(endTime)
                                .totalAmount(10000)
                                .pricePerSlotSnapshot(2500)
                                .build();
        }

        private Space space() {
                return Space.builder()
                                .id(SPACE_ID)
                                .openingTime(LocalTime.of(9, 0))
                                .closingTime(LocalTime.of(22, 0))
                                .build();
        }

        @Test
        @DisplayName("예약이 존재하지 않으면 RESERVATION_NOT_FOUND 예외가 발생한다")
        void extend_reservationNotFound_throwsException() {
                given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(Optional.empty());

                assertThatThrownBy(() -> extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, newEndTime))
                                .isInstanceOf(BusinessException.class)
                                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);

                verifyNoInteractions(spaceRepository, creditService);
        }

        @Test
        @DisplayName("예약 소유자가 아니면 FORBIDDEN_NOT_OWNER 예외가 발생한다")
        void extend_notOwner_throwsException() {
                given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(Optional.of(SPACE_ID));
                given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()));
                given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
                                .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)));

                assertThatThrownBy(() -> extendService.extend(OTHER_MEMBER_ID, RESERVATION_ID, endTime, newEndTime))
                                .isInstanceOf(BusinessException.class)
                                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER);

                verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("공간 운영시간(closingTime)을 초과하여 연장 시도하면 INVALID_RESERVATION_TIME 예외가 발생한다")
        void extend_exceedsClosingTime_throwsException() {
                LocalDateTime exceedEndTime = LocalDateTime.of(2026, 9, 20, 23, 0); // 공간 22:00 마감
                given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(Optional.of(SPACE_ID));
                given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()));
                given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
                                .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)));

                assertThatThrownBy(() -> extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, exceedEndTime))
                                .isInstanceOf(BusinessException.class)
                                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME);

                verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("30분 단위에 맞지 않는 연장 시각이면 INVALID_RESERVATION_TIME 예외가 발생한다")
        void extend_unalignedEndTime_throwsException() {
                LocalDateTime unalignedEndTime = LocalDateTime.of(2026, 9, 20, 14, 15);
                given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(Optional.of(SPACE_ID));
                given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()));
                given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
                                .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)));

                assertThatThrownBy(() -> extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, unalignedEndTime))
                                .isInstanceOf(BusinessException.class)
                                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME);

                verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("기존 예약과 다른 날짜로 연장 시도하면 INVALID_RESERVATION_TIME 예외가 발생한다")
        void extend_differentDate_throwsException() {
                LocalDateTime differentDateEndTime = LocalDateTime.of(2026, 9, 21, 10, 0);
                given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(Optional.of(SPACE_ID));
                given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()));
                given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
                                .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)));

                assertThatThrownBy(() -> extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, differentDateEndTime))
                                .isInstanceOf(BusinessException.class)
                                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME);

                verifyNoInteractions(creditService);
        }

        private Member activeMember() {
                return new Member("extend-test@slotkey.test", "테스트회원");
        }

        @Test
        @DisplayName("회원이 존재하지 않으면 AUTHENTICATION_REQUIRED 예외가 발생하고 슬롯을 확보하지 않는다")
        void extend_memberNotFound_throwsException() {
                given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(Optional.of(SPACE_ID));
                given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()));
                given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
                                .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)));
                given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.empty());

                assertThatThrownBy(() -> extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, newEndTime))
                                .isInstanceOf(BusinessException.class)
                                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTHENTICATION_REQUIRED);

                verify(reservationSlotService, never()).secureSlots(any(), any(), any());
                verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("탈퇴한 회원이면 ACCOUNT_WITHDRAWN 예외가 발생하고 슬롯을 확보하지 않는다")
        void extend_memberWithdrawn_throwsException() {
                given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(Optional.of(SPACE_ID));
                given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()));
                given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
                                .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)));

                Member withdrawn = new Member("extend-test@slotkey.test", "테스트회원");
                withdrawn.withdraw(now);
                given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(withdrawn));

                assertThatThrownBy(() -> extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, newEndTime))
                                .isInstanceOf(BusinessException.class)
                                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_WITHDRAWN);

                verify(reservationSlotService, never()).secureSlots(any(), any(), any());
                verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("연장 성공 시 추가 슬롯을 확보하고 크레딧을 차감하며 연장된 예약 정보를 반환한다")
        void extend_success() {
                Reservation reservation = activeReservation(ReservationStatus.IN_USE);
                Reservation extendedReservation = Reservation.builder()
                                .id(RESERVATION_ID)
                                .memberId(MEMBER_ID)
                                .spaceId(SPACE_ID)
                                .status(ReservationStatus.IN_USE)
                                .startTime(startTime)
                                .endTime(newEndTime)
                                .totalAmount(15000)
                                .pricePerSlotSnapshot(2500)
                                .createdAt(now.minusDays(1))
                                .build();

                given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(Optional.of(SPACE_ID));
                given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()));
                given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(reservation));
                given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(activeMember()));
                given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(extendedReservation));

                List<LocalDateTime> additionalSlots = List.of(endTime, endTime.plusMinutes(30));
                given(reservationSlotService.buildSlotStarts(endTime, newEndTime)).willReturn(additionalSlots);
                given(pricingService.calculateTotalAmount(2500, 2)).willReturn(5000);
                given(reservationRepository.extendIfEndTimeMatches(
                                eq(RESERVATION_ID), eq(endTime), eq(newEndTime), eq(15000),
                                eq(ReservationStatus.CONFIRMED), eq(ReservationStatus.IN_USE))).willReturn(1);

                ReservationResponse response = extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, newEndTime);

                assertThat(response.reservationId()).isEqualTo(RESERVATION_ID);

                // Space -> Reservation -> Member -> Slot 잠금 순서 검증
                InOrder inOrder = inOrder(spaceRepository, reservationRepository, memberRepository, reservationSlotService, creditService);
                inOrder.verify(spaceRepository).findByIdForShare(SPACE_ID);
                inOrder.verify(reservationRepository).findByIdForUpdate(RESERVATION_ID);
                inOrder.verify(memberRepository).findByIdForUpdate(MEMBER_ID);
                inOrder.verify(reservationSlotService).secureSlots(RESERVATION_ID, SPACE_ID, additionalSlots);
                inOrder.verify(creditService).charge(MEMBER_ID, RESERVATION_ID, 5000);
        }
}
