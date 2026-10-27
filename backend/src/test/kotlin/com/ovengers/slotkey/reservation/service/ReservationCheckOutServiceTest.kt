package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.access.service.DoorAccessTokenService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.support.anyNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.argThat
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class ReservationCheckOutServiceTest {
    companion object {
        private val MEMBER_ID: Long = 1L
        private val OTHER_MEMBER_ID: Long = 2L
        private val RESERVATION_ID: Long = 10L
        private val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 17, 13, 30)
        private val START_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 17, 13, 0)
        private val END_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 17, 14, 0)
    }

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    @Mock
    private lateinit var doorAccessTokenService: DoorAccessTokenService

    private lateinit var service: ReservationCheckOutService

    @BeforeEach
    fun setUp() {
        val clock: Clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

        service =
            ReservationCheckOutService(
                reservationRepository,
                reservationStatusHistoryRepository,
                clock,
                doorAccessTokenService,
            )
    }

    @Test
    @DisplayName("존재하지 않는 예약이면 체크아웃할 수 없다")
    fun shouldRejectCheckOutWhenReservationDoesNotExist() {
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.empty())

        assertThatThrownBy({
            service.checkOut(
                MEMBER_ID,
                RESERVATION_ID,
            )
        }).isInstanceOfSatisfying(
            BusinessException::class.java,
            { exception ->
                assertThat(exception.errorCode)
                    .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND)
            },
        )

        verify(doorAccessTokenService, never()).revokeByReservation(
            anyNonNull(),
            anyNonNull(),
            anyNonNull(),
        )
    }

    @Test
    @DisplayName("예약 소유자가 아니면 체크아웃할 수 없다")
    fun shouldRejectCheckOutWhenMemberIsNotOwner() {
        val reservation: Reservation =
            createReservation(
                MEMBER_ID,
                ReservationStatus.IN_USE,
                null,
            )

        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation))

        assertThatThrownBy({
            service.checkOut(
                OTHER_MEMBER_ID,
                RESERVATION_ID,
            )
        }).isInstanceOfSatisfying(
            BusinessException::class.java,
            { exception ->
                assertThat(exception.errorCode)
                    .isEqualTo(ErrorCode.FORBIDDEN_NOT_OWNER)
            },
        )

        verify(reservationRepository, never()).checkOutIfInUse(
            anyNonNull(),
            anyNonNull(),
            anyNonNull(),
            anyNonNull(),
        )

        verify(doorAccessTokenService, never()).revokeByReservation(
            anyNonNull(),
            anyNonNull(),
            anyNonNull(),
        )
    }

    @Test
    @DisplayName("예약 상태 전이에 실패하면 출입 토큰을 폐기하지 않는다")
    fun shouldNotRevokeTokenWhenCheckOutTransitionFails() {
        val reservation: Reservation =
            createReservation(
                MEMBER_ID,
                ReservationStatus.IN_USE,
                null,
            )

        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation))

        given(
            reservationRepository.checkOutIfInUse(
                RESERVATION_ID,
                NOW,
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED,
            ),
        ).willReturn(0)

        assertThatThrownBy({
            service.checkOut(
                MEMBER_ID,
                RESERVATION_ID,
            )
        }).isInstanceOfSatisfying(
            BusinessException::class.java,
            { exception ->
                assertThat(exception.errorCode)
                    .isEqualTo(ErrorCode.RESERVATION_STATE_CONFLICT)
            },
        )

        verify(doorAccessTokenService, never()).revokeByReservation(
            anyNonNull(),
            anyNonNull(),
            anyNonNull(),
        )

        verify(reservationStatusHistoryRepository, never()).save(anyNonNull())
    }

    @Test
    @DisplayName("체크아웃에 성공하면 출입 토큰을 폐기하고 상태 이력을 저장한다")
    fun shouldRevokeTokenAndSaveHistoryWhenCheckOutSucceeds() {
        val inUseReservation: Reservation =
            createReservation(
                MEMBER_ID,
                ReservationStatus.IN_USE,
                null,
            )

        val completedReservation: Reservation =
            createReservation(
                MEMBER_ID,
                ReservationStatus.COMPLETED,
                NOW,
            )

        given(reservationRepository.findById(RESERVATION_ID)).willReturn(
            Optional.of(inUseReservation),
            Optional.of(completedReservation),
        )

        given(
            reservationRepository.checkOutIfInUse(
                RESERVATION_ID,
                NOW,
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED,
            ),
        ).willReturn(1)

        val response: ReservationResponse =
            service.checkOut(
                MEMBER_ID,
                RESERVATION_ID,
            )

        verify(doorAccessTokenService).revokeByReservation(
            RESERVATION_ID,
            NOW,
            "CHECKED_OUT",
        )

        verify(reservationStatusHistoryRepository).save(
            argThat({ history ->
                history.reservationId.equals(RESERVATION_ID) &&
                    history.changedByMemberId!!.equals(MEMBER_ID) &&
                    history.fromStatus == ReservationStatus.IN_USE &&
                    history.toStatus == ReservationStatus.COMPLETED
            }),
        )

        assertThat(response.status).isEqualTo("COMPLETED")

        assertThat(response.checkedOutAt).isEqualTo(NOW)
    }

    private fun createReservation(
        memberId: Long,
        status: ReservationStatus,
        checkedOutAt: LocalDateTime?,
    ): Reservation =
        Reservation
            .builder()
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
            .build()
}
