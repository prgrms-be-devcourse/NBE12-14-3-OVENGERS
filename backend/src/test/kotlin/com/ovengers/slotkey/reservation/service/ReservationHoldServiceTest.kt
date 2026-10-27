package com.ovengers.slotkey.reservation.service
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.captureNonNull
import com.ovengers.slotkey.support.eqNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.argThat
import org.mockito.BDDMockito.given
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class ReservationHoldServiceTest {
    companion object {
        private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }

    @Mock
    private lateinit var memberRepository: MemberRepository

    @Mock
    private lateinit var spaceRepository: SpaceRepository

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    @Mock
    private lateinit var pricingService: PricingService

    @Mock
    private lateinit var reservationSlotService: ReservationSlotService

    private lateinit var clock: Clock
    private lateinit var reservationHoldService: ReservationHoldService
    private lateinit var now: LocalDateTime
    private lateinit var startTime: LocalDateTime
    private lateinit var endTime: LocalDateTime

    @BeforeEach
    fun setUp() {
        now = LocalDateTime.of(2026, 9, 17, 10, 0)
        clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE)
        reservationHoldService =
            ReservationHoldService(
                memberRepository,
                spaceRepository,
                reservationRepository,
                reservationStatusHistoryRepository,
                pricingService,
                reservationSlotService,
                clock,
            )
        startTime = LocalDateTime.of(2026, 9, 17, 14, 0)
        endTime = LocalDateTime.of(2026, 9, 17, 16, 0)
    }

    private fun activeSpace(): Space =
        Space
            .builder()
            .id(1L)
            .name("컨퍼런스 룸")
            .location("서울")
            .capacity(10)
            .pricePerSlot(5000L)
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(22, 0))
            .status(SpaceStatus.ACTIVE)
            .version(0)
            .build()

    private fun inactiveSpace(): Space =
        Space
            .builder()
            .id(1L)
            .name("컨퍼런스 룸")
            .location("서울")
            .capacity(10)
            .pricePerSlot(5000L)
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(22, 0))
            .status(SpaceStatus.INACTIVE)
            .version(0)
            .build()

    private fun savedReservationOf(captured: Reservation): Reservation =
        Reservation
            .builder()
            .id(1L)
            .memberId(captured.getMemberId())
            .spaceId(captured.getSpaceId())
            .startTime(captured.getStartTime())
            .endTime(captured.getEndTime())
            .status(captured.getStatus())
            .pricePerSlotSnapshot(captured.getPricePerSlotSnapshot())
            .totalAmount(captured.getTotalAmount())
            .holdExpiresAt(captured.holdExpiresAt)
            .createdAt(captured.getCreatedAt())
            .build()

    @Test
    @DisplayName("존재하지 않는 공간이면 SPACE_NOT_FOUND 예외가 발생한다")
    fun createHold_spaceNotFound_throwsException() {
        given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.empty())

        assertThatThrownBy({ reservationHoldService.createHold(1L, 1L, startTime, endTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_NOT_FOUND)
    }

    @Test
    @DisplayName("비활성 공간이면 SPACE_INACTIVE 예외가 발생한다")
    fun createHold_inactiveSpace_throwsException() {
        given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(inactiveSpace()))

        assertThatThrownBy({ reservationHoldService.createHold(1L, 1L, startTime, endTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_INACTIVE)
    }

    @Test
    @DisplayName("운영시간을 벗어난 시간 요청이면 ReservationTimePolicy의 예외가 그대로 전파된다")
    fun createHold_invalidTime_propagatesException() {
        given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(activeSpace()))
        val beforeOpening: LocalDateTime = LocalDateTime.of(2026, 9, 18, 8, 0)
        val beforeOpeningEnd: LocalDateTime = LocalDateTime.of(2026, 9, 18, 9, 0)

        assertThatThrownBy({ reservationHoldService.createHold(1L, 1L, beforeOpening, beforeOpeningEnd) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)

        verify(reservationRepository, never()).save(anyNonNull())
    }

    @Test
    @DisplayName("HOLD 생성에 성공하면 만료 시각(now+10분)과 가격 스냅샷이 반영된 예약을 저장하고 HELD 이력을 남긴다")
    fun createHold_success_savesHeldReservationAndHistory() {
        val space: Space = activeSpace()
        given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(space))
        given(pricingService.calculateSlotCount(startTime, endTime)).willReturn(4)
        given(pricingService.calculateTotalAmount(5000, 4)).willReturn(20000)
        given(reservationSlotService.buildSlotStarts(startTime, endTime))
            .willReturn(listOf(startTime, startTime.plusMinutes(30), startTime.plusMinutes(60), startTime.plusMinutes(90)))

        val reservationCaptor: ArgumentCaptor<Reservation> = ArgumentCaptor.forClass(Reservation::class.java)
        given(reservationRepository.save(captureNonNull(reservationCaptor)))
            .willAnswer({ invocation -> savedReservationOf(invocation.getArgument(0)) })
        val member: Member = Member("hold-test@slotkey.test", "테스트회원")
        given(memberRepository.findByIdForUpdate(100L)).willReturn(Optional.of(member))

        val response: ReservationResponse = reservationHoldService.createHold(100L, 1L, startTime, endTime)

        val saved: Reservation = reservationCaptor.value
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.HELD)
        assertThat(saved.getPricePerSlotSnapshot()).isEqualTo(5000)
        assertThat(saved.getTotalAmount()).isEqualTo(20000)
        assertThat(saved.holdExpiresAt).isEqualTo(now.plusMinutes(10))

        assertThat(response.reservationId).isEqualTo(1L)
        assertThat(response.status).isEqualTo("HELD")
        assertThat(response.spaceVersion).isEqualTo(0)

        val inOrder: InOrder = inOrder(memberRepository, reservationRepository, reservationSlotService, reservationStatusHistoryRepository)
        inOrder.verify(memberRepository).findByIdForUpdate(100L)
        inOrder.verify(reservationRepository).save(anyNonNull(Reservation::class.java))
        inOrder.verify(reservationSlotService).secureSlots(eqNonNull(1L), eqNonNull(1L), anyList())
        inOrder.verify(reservationStatusHistoryRepository).save(
            argThat({ history ->
                history.fromStatus == null &&
                    history.toStatus == ReservationStatus.HELD &&
                    history.reservationId.equals(1L)
            }),
        )
    }

    @Test
    @DisplayName("슬롯 확보에 실패하면 RESERVATION_SLOT_CONFLICT 예외가 그대로 전파되고 이력은 남지 않는다")
    fun createHold_slotConflict_propagatesException() {
        given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(activeSpace()))
        given(pricingService.calculateSlotCount(startTime, endTime)).willReturn(4)
        given(pricingService.calculateTotalAmount(5000, 4)).willReturn(20000)
        val member: Member = Member("hold-test@slotkey.test", "테스트회원")
        given(memberRepository.findByIdForUpdate(100L)).willReturn(Optional.of(member))
        given(reservationRepository.save(anyNonNull(Reservation::class.java)))
            .willAnswer({ invocation -> savedReservationOf(invocation.getArgument(0)) })
        given(reservationSlotService.buildSlotStarts(startTime, endTime)).willReturn(listOf(startTime))
        doThrow(BusinessException(ErrorCode.RESERVATION_SLOT_CONFLICT))
            .`when`(reservationSlotService)
            .secureSlots(eqNonNull(1L), eqNonNull(1L), anyList())

        assertThatThrownBy({ reservationHoldService.createHold(100L, 1L, startTime, endTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_SLOT_CONFLICT)

        verify(reservationStatusHistoryRepository, never()).save(anyNonNull())
    }

    @Test
    @DisplayName("회원 조회 결과가 없으면 AUTHENTICATION_REQUIRED 예외가 발생하고 예약·슬롯·이력은 저장되지 않는다")
    fun createHold_memberNotFound_throwsAuthenticationRequired() {
        val space: Space = activeSpace()
        given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(space))
        given(pricingService.calculateSlotCount(startTime, endTime)).willReturn(4)
        given(pricingService.calculateTotalAmount(5000, 4)).willReturn(20000)
        given(memberRepository.findByIdForUpdate(100L)).willReturn(Optional.empty())

        assertThatThrownBy({ reservationHoldService.createHold(100L, 1L, startTime, endTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTHENTICATION_REQUIRED)

        verify(reservationRepository, never()).save(anyNonNull())
        verify(reservationSlotService, never()).secureSlots(anyNonNull(), anyNonNull(), anyNonNull())
        verify(reservationStatusHistoryRepository, never()).save(anyNonNull())
    }

    @Test
    @DisplayName("탈퇴한 회원이면 ACCOUNT_WITHDRAWN 예외가 발생하고 예약·슬롯·이력은 저장되지 않는다")
    fun createHold_withdrawnMember_throwsAccountWithdrawn() {
        val space: Space = activeSpace()
        given(spaceRepository.findByIdForShare(1L)).willReturn(Optional.of(space))
        given(pricingService.calculateSlotCount(startTime, endTime)).willReturn(4)
        given(pricingService.calculateTotalAmount(5000, 4)).willReturn(20000)

        val member: Member = Member("hold-test@slotkey.test", "테스트회원")
        member.withdraw(now)
        given(memberRepository.findByIdForUpdate(100L)).willReturn(Optional.of(member))

        assertThatThrownBy({ reservationHoldService.createHold(100L, 1L, startTime, endTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_WITHDRAWN)

        verify(reservationRepository, never()).save(anyNonNull())
        verify(reservationSlotService, never()).secureSlots(anyNonNull(), anyNonNull(), anyNonNull())
        verify(reservationStatusHistoryRepository, never()).save(anyNonNull())
    }
}
