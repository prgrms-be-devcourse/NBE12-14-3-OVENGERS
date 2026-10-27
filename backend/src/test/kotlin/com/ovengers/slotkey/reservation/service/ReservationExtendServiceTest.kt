package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.credit.service.CreditService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.eqNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class ReservationExtendServiceTest {
    companion object {
        private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val MEMBER_ID: Long = 1L
        private val OTHER_MEMBER_ID: Long = 2L
        private val RESERVATION_ID: Long = 10L
        private val SPACE_ID: Long = 5L
    }

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var spaceRepository: SpaceRepository

    @Mock
    private lateinit var memberRepository: MemberRepository

    @Mock
    private lateinit var reservationSlotService: ReservationSlotService

    @Mock
    private lateinit var pricingService: PricingService

    @Mock
    private lateinit var creditService: CreditService

    private lateinit var clock: Clock
    private lateinit var extendService: ReservationExtendService
    private lateinit var now: LocalDateTime
    private lateinit var startTime: LocalDateTime
    private lateinit var endTime: LocalDateTime
    private lateinit var newEndTime: LocalDateTime

    @BeforeEach
    fun setUp() {
        now = LocalDateTime.of(2026, 9, 20, 10, 0)
        clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE)
        extendService =
            ReservationExtendService(
                reservationRepository,
                spaceRepository,
                memberRepository,
                reservationSlotService,
                pricingService,
                creditService,
                clock,
            )

        startTime = LocalDateTime.of(2026, 9, 20, 11, 0)
        endTime = LocalDateTime.of(2026, 9, 20, 13, 0)
        newEndTime = LocalDateTime.of(2026, 9, 20, 14, 0)
    }

    private fun activeReservation(status: ReservationStatus): Reservation =
        Reservation
            .builder()
            .id(RESERVATION_ID)
            .memberId(MEMBER_ID)
            .spaceId(SPACE_ID)
            .status(status)
            .startTime(startTime)
            .endTime(endTime)
            .totalAmount(10000)
            .pricePerSlotSnapshot(2500)
            .build()

    private fun space(): Space =
        Space
            .builder()
            .id(SPACE_ID)
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(22, 0))
            .build()

    @Test
    @DisplayName("예약이 존재하지 않으면 RESERVATION_NOT_FOUND 예외가 발생한다")
    fun extend_reservationNotFound_throwsException() {
        given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(null)

        assertThatThrownBy({ extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, newEndTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND)

        verifyNoInteractions(spaceRepository, creditService)
    }

    @Test
    @DisplayName("예약 소유자가 아니면 FORBIDDEN_NOT_OWNER 예외가 발생한다")
    fun extend_notOwner_throwsException() {
        given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(SPACE_ID)
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()))
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
            .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)))

        assertThatThrownBy({ extendService.extend(OTHER_MEMBER_ID, RESERVATION_ID, endTime, newEndTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER)

        verifyNoInteractions(creditService)
    }

    @Test
    @DisplayName("공간 운영시간(closingTime)을 초과하여 연장 시도하면 INVALID_RESERVATION_TIME 예외가 발생한다")
    fun extend_exceedsClosingTime_throwsException() {
        val exceedEndTime: LocalDateTime = LocalDateTime.of(2026, 9, 20, 23, 0) // 공간 22:00 마감
        given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(SPACE_ID)
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()))
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
            .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)))

        assertThatThrownBy({ extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, exceedEndTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)

        verifyNoInteractions(creditService)
    }

    @Test
    @DisplayName("30분 단위에 맞지 않는 연장 시각이면 INVALID_RESERVATION_TIME 예외가 발생한다")
    fun extend_unalignedEndTime_throwsException() {
        val unalignedEndTime: LocalDateTime = LocalDateTime.of(2026, 9, 20, 14, 15)
        given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(SPACE_ID)
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()))
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
            .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)))

        assertThatThrownBy({ extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, unalignedEndTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)

        verifyNoInteractions(creditService)
    }

    @Test
    @DisplayName("기존 예약과 다른 날짜로 연장 시도하면 INVALID_RESERVATION_TIME 예외가 발생한다")
    fun extend_differentDate_throwsException() {
        val differentDateEndTime: LocalDateTime = LocalDateTime.of(2026, 9, 21, 10, 0)
        given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(SPACE_ID)
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()))
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
            .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)))

        assertThatThrownBy({ extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, differentDateEndTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)

        verifyNoInteractions(creditService)
    }

    private fun activeMember(): Member = Member("extend-test@slotkey.test", "테스트회원")

    @Test
    @DisplayName("회원이 존재하지 않으면 AUTHENTICATION_REQUIRED 예외가 발생하고 슬롯을 확보하지 않는다")
    fun extend_memberNotFound_throwsException() {
        given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(SPACE_ID)
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()))
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
            .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)))
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.empty())

        assertThatThrownBy({ extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, newEndTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTHENTICATION_REQUIRED)

        verify(reservationSlotService, never()).secureSlots(anyNonNull(), anyNonNull(), anyNonNull())
        verifyNoInteractions(creditService)
    }

    @Test
    @DisplayName("탈퇴한 회원이면 ACCOUNT_WITHDRAWN 예외가 발생하고 슬롯을 확보하지 않는다")
    fun extend_memberWithdrawn_throwsException() {
        given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(SPACE_ID)
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()))
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID))
            .willReturn(Optional.of(activeReservation(ReservationStatus.CONFIRMED)))

        val withdrawn: Member = Member("extend-test@slotkey.test", "테스트회원")
        withdrawn.withdraw(now)
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(withdrawn))

        assertThatThrownBy({ extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, newEndTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_WITHDRAWN)

        verify(reservationSlotService, never()).secureSlots(anyNonNull(), anyNonNull(), anyNonNull())
        verifyNoInteractions(creditService)
    }

    @Test
    @DisplayName("연장 성공 시 추가 슬롯을 확보하고 크레딧을 차감하며 연장된 예약 정보를 반환한다")
    fun extend_success() {
        val reservation: Reservation = activeReservation(ReservationStatus.IN_USE)
        val extendedReservation: Reservation =
            Reservation
                .builder()
                .id(RESERVATION_ID)
                .memberId(MEMBER_ID)
                .spaceId(SPACE_ID)
                .status(ReservationStatus.IN_USE)
                .startTime(startTime)
                .endTime(newEndTime)
                .totalAmount(15000)
                .pricePerSlotSnapshot(2500)
                .createdAt(now.minusDays(1))
                .build()

        given(reservationRepository.findSpaceIdById(RESERVATION_ID)).willReturn(SPACE_ID)
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(space()))
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(reservation))
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(activeMember()))
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(extendedReservation))

        val additionalSlots: List<LocalDateTime> = listOf(endTime, endTime.plusMinutes(30))
        given(reservationSlotService.buildSlotStarts(endTime, newEndTime)).willReturn(additionalSlots)
        given(pricingService.calculateTotalAmount(2500, 2)).willReturn(5000)
        given(
            reservationRepository.extendIfEndTimeMatches(
                eqNonNull(RESERVATION_ID),
                eqNonNull(endTime),
                eqNonNull(newEndTime),
                eqNonNull(15000),
                eqNonNull(ReservationStatus.CONFIRMED),
                eqNonNull(ReservationStatus.IN_USE),
            ),
        ).willReturn(1)

        val response: ReservationResponse = extendService.extend(MEMBER_ID, RESERVATION_ID, endTime, newEndTime)

        assertThat(response.reservationId).isEqualTo(RESERVATION_ID)

        // Space -> Reservation -> Member -> Slot 잠금 순서 검증
        val inOrder: InOrder =
            inOrder(spaceRepository, reservationRepository, memberRepository, reservationSlotService, creditService)
        inOrder.verify(spaceRepository).findByIdForShare(SPACE_ID)
        inOrder.verify(reservationRepository).findByIdForUpdate(RESERVATION_ID)
        inOrder.verify(memberRepository).findByIdForUpdate(MEMBER_ID)
        inOrder.verify(reservationSlotService).secureSlots(RESERVATION_ID, SPACE_ID, additionalSlots)
        inOrder.verify(creditService).charge(MEMBER_ID, RESERVATION_ID, 5000)
    }
}
