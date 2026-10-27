package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.access.service.DoorAccessTokenService
import com.ovengers.slotkey.credit.service.CreditService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.outbox.SlotReleasedRecorder
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.support.anyNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.argThat
import org.mockito.BDDMockito.given
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class ReservationCancelServiceTest {
    companion object {
        private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val MEMBER_ID: Long = 1L
        private val OTHER_MEMBER_ID: Long = 2L
        private val RESERVATION_ID: Long = 10L
        private val TOTAL_AMOUNT: Int = 20000
    }

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var memberRepository: MemberRepository

    @Mock
    private lateinit var reservationSlotRepository: ReservationSlotRepository

    @Mock
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    @Mock
    private lateinit var creditService: CreditService

    @Mock
    private lateinit var doorAccessTokenService: DoorAccessTokenService

    private lateinit var clock: Clock

    @Mock
    private lateinit var slotReleasedRecorder: SlotReleasedRecorder
    private lateinit var cancelService: ReservationCancelService
    private lateinit var now: LocalDateTime
    private lateinit var startTime: LocalDateTime

    @BeforeEach
    fun setUp() {
        now = LocalDateTime.of(2026, 9, 17, 13, 0)
        clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE)
        cancelService =
            ReservationCancelService(
                reservationRepository,
                memberRepository,
                reservationSlotRepository,
                reservationStatusHistoryRepository,
                creditService,
                doorAccessTokenService,
                clock,
                slotReleasedRecorder,
            )
        startTime = LocalDateTime.of(2026, 9, 17, 14, 0) // now(13:00) + 1시간
    }

    private fun activeMember(): Member = Member("user@slotkey.test", "회원")

    private fun confirmedReservation(memberId: Long): Reservation =
        Reservation
            .builder()
            .id(RESERVATION_ID)
            .memberId(memberId)
            .spaceId(5L)
            .startTime(startTime)
            .endTime(startTime.plusHours(1))
            .status(ReservationStatus.CONFIRMED)
            .pricePerSlotSnapshot(5000)
            .totalAmount(TOTAL_AMOUNT)
            .createdAt(now.minusDays(1))
            .build()

    @Test
    @DisplayName("존재하지 않는 예약이면 RESERVATION_NOT_FOUND 예외가 발생한다")
    fun cancel_reservationNotFound_throwsException() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty())

        assertThatThrownBy({ cancelService.cancel(MEMBER_ID, RESERVATION_ID) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND)
    }

    @Test
    @DisplayName("본인이 아닌 예약을 취소 요청하면 FORBIDDEN_NOT_OWNER 예외가 발생하고 취소·환불이 실행되지 않는다")
    fun cancel_notOwner_throwsException() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(confirmedReservation(MEMBER_ID)))

        assertThatThrownBy({ cancelService.cancel(OTHER_MEMBER_ID, RESERVATION_ID) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER)

        verify(reservationRepository, never()).cancelIfConfirmedAndBeforeStart(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull())
        verify(creditService, never()).refund(anyNonNull(), anyNonNull(), anyInt())
        verify(doorAccessTokenService, never()).revokeByReservation(anyNonNull(), anyNonNull(), anyNonNull())
        verify(slotReleasedRecorder, never()).record(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyNonNull(),
        )
    }

    @Test
    @DisplayName("취소할 수 없는 상태(이미 시작됨 등)이면 RESERVATION_STATE_CONFLICT 예외가 발생하고 슬롯·환불 처리를 하지 않는다")
    fun cancel_conflictState_throwsException() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(confirmedReservation(MEMBER_ID)))
        given(
            reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID,
                now,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(0)

        assertThatThrownBy({ cancelService.cancel(MEMBER_ID, RESERVATION_ID) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

        verify(reservationSlotRepository, never()).deleteByReservationId(anyNonNull())
        verify(creditService, never()).refund(anyNonNull(), anyNonNull(), anyInt())
        verify(doorAccessTokenService, never()).revokeByReservation(anyNonNull(), anyNonNull(), anyNonNull())
    }

    @Test
    @DisplayName("시작 정확히 1시간 전에 취소하면 전액 환불되고 위약금은 기록하지 않는다")
    fun cancel_exactlyOneHourBefore_fullRefund() {
        val reservation: Reservation = confirmedReservation(MEMBER_ID)
        given(reservationRepository.findById(RESERVATION_ID))
            .willReturn(Optional.of(reservation), Optional.of(reservation))
        given(
            reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID,
                now,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(1)
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(activeMember()))

        val response: ReservationResponse = cancelService.cancel(MEMBER_ID, RESERVATION_ID)

        verify(creditService).refund(MEMBER_ID, RESERVATION_ID, TOTAL_AMOUNT)
        verify(creditService, never()).penalize(anyNonNull(), anyNonNull(), anyInt())
        assertThat(response.refundAmount).isEqualTo(TOTAL_AMOUNT)
        assertThat(response.penaltyAmount).isEqualTo(0)
    }

    @Test
    @DisplayName("시작 1시간 전 마감이 지난 뒤 취소하면 50%만 환불되고 나머지는 위약금으로 기록한다")
    fun cancel_afterOneHourDeadline_halfRefund() {
        val lateNow: LocalDateTime = now.plusMinutes(1) // 13:01, 마감(13:00) 1분 경과
        val lateClock: Clock = Clock.fixed(lateNow.atZone(ZONE).toInstant(), ZONE)
        cancelService =
            ReservationCancelService(
                reservationRepository,
                memberRepository,
                reservationSlotRepository,
                reservationStatusHistoryRepository,
                creditService,
                doorAccessTokenService,
                lateClock,
                slotReleasedRecorder,
            )
        val reservation: Reservation = confirmedReservation(MEMBER_ID)
        given(reservationRepository.findById(RESERVATION_ID))
            .willReturn(Optional.of(reservation), Optional.of(reservation))
        given(
            reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID,
                lateNow,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(1)
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(activeMember()))

        val response: ReservationResponse = cancelService.cancel(MEMBER_ID, RESERVATION_ID)

        verify(creditService).refund(MEMBER_ID, RESERVATION_ID, TOTAL_AMOUNT)
        verify(creditService).penalize(MEMBER_ID, RESERVATION_ID, TOTAL_AMOUNT / 2)
        assertThat(response.refundAmount).isEqualTo(TOTAL_AMOUNT / 2)
        assertThat(response.penaltyAmount).isEqualTo(TOTAL_AMOUNT / 2)
    }

    @Test
    @DisplayName("정상 취소 시 잠금 순서(Reservation UPDATE -> Member X -> Slot DELETE -> Refund)를 준수하며 슬롯을 반환하고 상태 이력을 저장한다")
    fun cancel_success_deletesSlotsAndSavesHistory() {
        val reservation: Reservation = confirmedReservation(MEMBER_ID)
        given(reservationRepository.findById(RESERVATION_ID))
            .willReturn(Optional.of(reservation), Optional.of(reservation))
        given(
            reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID,
                now,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(1)
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(activeMember()))

        cancelService.cancel(MEMBER_ID, RESERVATION_ID)

        val inOrder: InOrder = inOrder(reservationRepository, memberRepository, reservationSlotRepository, creditService)
        inOrder.verify(reservationRepository).cancelIfConfirmedAndBeforeStart(
            RESERVATION_ID,
            now,
            ReservationStatus.CONFIRMED,
            ReservationStatus.CANCELLED,
        )
        inOrder.verify(memberRepository).findByIdForUpdate(MEMBER_ID)
        inOrder.verify(reservationSlotRepository).deleteByReservationId(RESERVATION_ID)
        inOrder.verify(creditService).refund(MEMBER_ID, RESERVATION_ID, TOTAL_AMOUNT)

        verify(doorAccessTokenService).revokeByReservation(RESERVATION_ID, now, "CANCELLED")
        verify(reservationStatusHistoryRepository).save(
            argThat({ history ->
                history.fromStatus == ReservationStatus.CONFIRMED &&
                    history.toStatus == ReservationStatus.CANCELLED &&
                    history.changedByMemberId!!.equals(MEMBER_ID)
            }),
        )
        verify(slotReleasedRecorder).record(RESERVATION_ID, 5L, SlotReleasedRecorder.Reason.CANCELLED)
    }

    @Test
    @DisplayName("회원이 존재하지 않으면 AUTHENTICATION_REQUIRED 예외가 발생하고 슬롯 삭제 및 환불이 실행되지 않는다")
    fun cancel_memberNotFound_throwsAuthenticationRequired() {
        val reservation: Reservation = confirmedReservation(MEMBER_ID)
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation))
        given(
            reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID,
                now,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(1)
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.empty())

        assertThatThrownBy({ cancelService.cancel(MEMBER_ID, RESERVATION_ID) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTHENTICATION_REQUIRED)

        verify(reservationSlotRepository, never()).deleteByReservationId(anyNonNull())
        verify(creditService, never()).refund(anyNonNull(), anyNonNull(), anyInt())
    }

    @Test
    @DisplayName("탈퇴한 회원이면 ACCOUNT_WITHDRAWN 예외가 발생하고 슬롯 삭제 및 환불이 실행되지 않는다")
    fun cancel_withdrawnMember_throwsAccountWithdrawn() {
        val reservation: Reservation = confirmedReservation(MEMBER_ID)
        val withdrawnMember: Member = activeMember()
        withdrawnMember.withdraw(now)

        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation))
        given(
            reservationRepository.cancelIfConfirmedAndBeforeStart(
                RESERVATION_ID,
                now,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(1)
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(withdrawnMember))

        assertThatThrownBy({ cancelService.cancel(MEMBER_ID, RESERVATION_ID) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_WITHDRAWN)

        verify(reservationSlotRepository, never()).deleteByReservationId(anyNonNull())
        verify(creditService, never()).refund(anyNonNull(), anyNonNull(), anyInt())
    }

    private fun heldReservation(status: ReservationStatus): Reservation =
        Reservation
            .builder()
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
            .build()

    @Test
    @DisplayName("결제 대기(HELD) 예약을 취소하면 슬롯을 반환하고 환불·위약금 없이 CANCELLED가 되며 빈자리 이벤트를 기록한다")
    fun cancel_held_releasesSlotWithoutRefund() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(
            Optional.of(heldReservation(ReservationStatus.HELD)),
            Optional.of(heldReservation(ReservationStatus.CANCELLED)),
        )
        given(
            reservationRepository.cancelIfHeld(
                RESERVATION_ID,
                now,
                ReservationStatus.HELD,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(1)

        val response: ReservationResponse = cancelService.cancel(MEMBER_ID, RESERVATION_ID)

        assertThat(response.status).isEqualTo("CANCELLED")
        assertThat(response.refundAmount).isZero()
        assertThat(response.penaltyAmount).isZero()
        verify(reservationSlotRepository).deleteByReservationId(RESERVATION_ID)
        verify(reservationStatusHistoryRepository).save(anyNonNull())
        verify(slotReleasedRecorder).record(RESERVATION_ID, 5L, SlotReleasedRecorder.Reason.CANCELLED)
        verify(reservationRepository, never()).cancelIfConfirmedAndBeforeStart(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull())
        verify(creditService, never()).refund(anyNonNull(), anyNonNull(), anyInt())
        verify(creditService, never()).penalize(anyNonNull(), anyNonNull(), anyInt())
    }

    @Test
    @DisplayName("결제 대기 취소 중 이미 결제·만료로 상태가 바뀌었으면 RESERVATION_STATE_CONFLICT이고 슬롯·이벤트를 건드리지 않는다")
    fun cancel_held_conflictWhenStateChanged() {
        given(reservationRepository.findById(RESERVATION_ID))
            .willReturn(Optional.of(heldReservation(ReservationStatus.HELD)))
        given(
            reservationRepository.cancelIfHeld(
                RESERVATION_ID,
                now,
                ReservationStatus.HELD,
                ReservationStatus.CANCELLED,
            ),
        ).willReturn(0)

        assertThatThrownBy({ cancelService.cancel(MEMBER_ID, RESERVATION_ID) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

        verify(reservationSlotRepository, never()).deleteByReservationId(anyNonNull())
        verify(slotReleasedRecorder, never()).record(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyNonNull(),
        )
    }
}
