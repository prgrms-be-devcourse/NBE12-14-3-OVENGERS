package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.repository.MemberRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservationHoldServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationStatusHistoryRepository reservationStatusHistoryRepository;
    @Mock
    private PricingService pricingService;
    @Mock
    private ReservationSlotService reservationSlotService;

    private Clock clock;
    private ReservationHoldService reservationHoldService;
    private LocalDateTime now;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.of(2026, 9, 17, 10, 0);
        clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
        reservationHoldService = new ReservationHoldService(
                memberRepository, spaceRepository, reservationRepository,
                reservationStatusHistoryRepository, pricingService,
                reservationSlotService, clock);
        startTime = LocalDateTime.of(2026, 9, 17, 14, 0);
        endTime = LocalDateTime.of(2026, 9, 17, 16, 0);
    }

    private Space activeSpace() {
        return Space.builder()
                .id(1L)
                .name("컨퍼런스 룸")
                .location("서울")
                .capacity(10)
                .pricePerSlot(5000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(22, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build();
    }

    private Space inactiveSpace() {
        return Space.builder()
                .id(1L)
                .name("컨퍼런스 룸")
                .location("서울")
                .capacity(10)
                .pricePerSlot(5000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(22, 0))
                .status(SpaceStatus.INACTIVE)
                .version(0)
                .build();
    }

    private Reservation savedReservationOf(Reservation captured) {
        return Reservation.builder()
                .id(1L)
                .memberId(captured.getMemberId())
                .spaceId(captured.getSpaceId())
                .startTime(captured.getStartTime())
                .endTime(captured.getEndTime())
                .status(captured.getStatus())
                .pricePerSlotSnapshot(captured.getPricePerSlotSnapshot())
                .totalAmount(captured.getTotalAmount())
                .holdExpiresAt(captured.getHoldExpiresAt())
                .createdAt(captured.getCreatedAt())
                .build();
    }

    @Test
    @DisplayName("존재하지 않는 공간이면 SPACE_NOT_FOUND 예외가 발생한다")
    void createHold_spaceNotFound_throwsException() {
            given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> reservationHoldService.createHold(1L, 1L, startTime, endTime))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_NOT_FOUND);
    }

    @Test
    @DisplayName("비활성 공간이면 SPACE_INACTIVE 예외가 발생한다")
    void createHold_inactiveSpace_throwsException() {
            given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(inactiveSpace()));

        assertThatThrownBy(() -> reservationHoldService.createHold(1L, 1L, startTime, endTime))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_INACTIVE);
    }

    @Test
    @DisplayName("운영시간을 벗어난 시간 요청이면 ReservationTimePolicy의 예외가 그대로 전파된다")
    void createHold_invalidTime_propagatesException() {
            given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(activeSpace()));
        LocalDateTime beforeOpening = LocalDateTime.of(2026, 9, 18, 8, 0);
        LocalDateTime beforeOpeningEnd = LocalDateTime.of(2026, 9, 18, 9, 0);

        assertThatThrownBy(() -> reservationHoldService.createHold(1L, 1L, beforeOpening, beforeOpeningEnd))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME);

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("HOLD 생성에 성공하면 만료 시각(now+10분)과 가격 스냅샷이 반영된 예약을 저장하고 HELD 이력을 남긴다")
    void createHold_success_savesHeldReservationAndHistory() {
        Space space = activeSpace();
        given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(space));
        given(pricingService.calculateSlotCount(startTime, endTime)).willReturn(4);
        given(pricingService.calculateTotalAmount(5000, 4)).willReturn(20000);
        given(reservationSlotService.buildSlotStarts(startTime, endTime))
                .willReturn(List.of(startTime, startTime.plusMinutes(30), startTime.plusMinutes(60), startTime.plusMinutes(90)));

        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        given(reservationRepository.save(reservationCaptor.capture()))
                .willAnswer(invocation -> savedReservationOf(invocation.getArgument(0)));
        Member member = new Member("hold-test@slotkey.test", "테스트회원");
        given(memberRepository.findByIdForUpdate(100L)).willReturn(Optional.of(member));

        ReservationResponse response = reservationHoldService.createHold(100L, 1L, startTime, endTime);

        Reservation saved = reservationCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(saved.getPricePerSlotSnapshot()).isEqualTo(5000);
        assertThat(saved.getTotalAmount()).isEqualTo(20000);
        assertThat(saved.getHoldExpiresAt()).isEqualTo(now.plusMinutes(10));

        assertThat(response.reservationId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("HELD");
        assertThat(response.spaceVersion()).isEqualTo(0);

        InOrder inOrder = inOrder(memberRepository, reservationRepository, reservationSlotService, reservationStatusHistoryRepository);
        inOrder.verify(memberRepository).findByIdForUpdate(100L);
        inOrder.verify(reservationRepository).save(any(Reservation.class));
        inOrder.verify(reservationSlotService).secureSlots(eq(1L), eq(1L), anyList());
        inOrder.verify(reservationStatusHistoryRepository).save(argThat(history ->
                history.getFromStatus() == null
                        && history.getToStatus() == ReservationStatus.HELD
                        && history.getReservationId().equals(1L)));
    }

    @Test
    @DisplayName("슬롯 확보에 실패하면 RESERVATION_SLOT_CONFLICT 예외가 그대로 전파되고 이력은 남지 않는다")
    void createHold_slotConflict_propagatesException() {
        given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(activeSpace()));
        given(pricingService.calculateSlotCount(startTime, endTime)).willReturn(4);
        given(pricingService.calculateTotalAmount(5000, 4)).willReturn(20000);
        Member member = new Member("hold-test@slotkey.test", "테스트회원");
        given(memberRepository.findByIdForUpdate(100L)).willReturn(Optional.of(member));
        given(reservationRepository.save(any(Reservation.class)))
                .willAnswer(invocation -> savedReservationOf(invocation.getArgument(0)));
        given(reservationSlotService.buildSlotStarts(startTime, endTime)).willReturn(List.of(startTime));
        doThrow(new BusinessException(ErrorCode.RESERVATION_SLOT_CONFLICT))
                .when(reservationSlotService).secureSlots(eq(1L), eq(1L), anyList());

        assertThatThrownBy(() -> reservationHoldService.createHold(100L, 1L, startTime, endTime))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_SLOT_CONFLICT);

        verify(reservationStatusHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("회원 조회 결과가 없으면 AUTHENTICATION_REQUIRED 예외가 발생하고 예약·슬롯·이력은 저장되지 않는다")
    void createHold_memberNotFound_throwsAuthenticationRequired() {
        Space space = activeSpace();
        given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(space));
        given(pricingService.calculateSlotCount(startTime, endTime)).willReturn(4);
        given(pricingService.calculateTotalAmount(5000, 4)).willReturn(20000);
        given(memberRepository.findByIdForUpdate(100L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> reservationHoldService.createHold(100L, 1L, startTime, endTime))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTHENTICATION_REQUIRED);

        verify(reservationRepository, never()).save(any());
        verify(reservationSlotService, never()).secureSlots(any(), any(), any());
        verify(reservationStatusHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("탈퇴한 회원이면 ACCOUNT_WITHDRAWN 예외가 발생하고 예약·슬롯·이력은 저장되지 않는다")
    void createHold_withdrawnMember_throwsAccountWithdrawn() {
        Space space = activeSpace();
        given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(space));
        given(pricingService.calculateSlotCount(startTime, endTime)).willReturn(4);
        given(pricingService.calculateTotalAmount(5000, 4)).willReturn(20000);

        Member member = new Member("hold-test@slotkey.test", "테스트회원");
        member.withdraw(now);
        given(memberRepository.findByIdForUpdate(100L)).willReturn(Optional.of(member));

        assertThatThrownBy(() -> reservationHoldService.createHold(100L, 1L, startTime, endTime))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_WITHDRAWN);

        verify(reservationRepository, never()).save(any());
        verify(reservationSlotService, never()).secureSlots(any(), any(), any());
        verify(reservationStatusHistoryRepository, never()).save(any());
    }
}
