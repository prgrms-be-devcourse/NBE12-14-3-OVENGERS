package com.ovengers.slotkey.reservation.scheduler

import com.ovengers.slotkey.access.service.DoorAccessTokenService
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.reservation.service.ReservationHoldExpirationService
import com.ovengers.slotkey.support.anyNonNull
import org.assertj.core.api.Assertions.assertThat
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
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockitoExtension::class)
public class ReservationBatchProcessorTest {
    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var reservationSlotRepository: ReservationSlotRepository

    @Mock
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    @Mock
    private lateinit var doorAccessTokenService: DoorAccessTokenService

    @Mock
    private lateinit var reservationHoldExpirationService: ReservationHoldExpirationService

    @Mock
    private lateinit var completedReservation: Reservation

    private lateinit var processor: ReservationBatchProcessor

    @BeforeEach
    fun setUp() {
        processor =
            ReservationBatchProcessor(
                reservationRepository,
                reservationSlotRepository,
                reservationStatusHistoryRepository,
                doorAccessTokenService,
                reservationHoldExpirationService,
            )
    }

    @Test
    @DisplayName("expireHold는 ReservationHoldExpirationService에 위임하여 성공 결과를 반환한다")
    fun shouldDelegateToExpireHoldServiceAndReturnTrueWhenSucceeds() {
        given(reservationHoldExpirationService.expireHold(RESERVATION_ID, NOW)).willReturn(true)

        val result: Boolean = processor.expireHold(RESERVATION_ID, NOW)

        assertThat(result).isTrue()
        verify(reservationHoldExpirationService).expireHold(RESERVATION_ID, NOW)
    }

    @Test
    @DisplayName("expireHold는 ReservationHoldExpirationService에 위임하여 실패 결과를 반환한다")
    fun shouldDelegateToExpireHoldServiceAndReturnFalseWhenFails() {
        given(reservationHoldExpirationService.expireHold(RESERVATION_ID, NOW)).willReturn(false)

        val result: Boolean = processor.expireHold(RESERVATION_ID, NOW)

        assertThat(result).isFalse()
        verify(reservationHoldExpirationService).expireHold(RESERVATION_ID, NOW)
    }

    @Test
    @DisplayName("노쇼 처리에 성공하면 출입 토큰을 폐기하고 상태 이력을 저장한다")
    fun shouldRevokeTokenAndSaveHistoryWhenMarkNoShowSucceeds() {
        given(
            reservationRepository.markNoShowIfNotCheckedIn(
                RESERVATION_ID,
                NOW.minus(ReservationBatchProcessor.CHECK_IN_WINDOW),
                ReservationStatus.CONFIRMED,
                ReservationStatus.NO_SHOW,
            ),
        ).willReturn(1)

        val result: Boolean =
            processor.markNoShow(
                RESERVATION_ID,
                NOW,
            )

        assertThat(result).isTrue()

        verify(reservationSlotRepository).deleteByReservationId(RESERVATION_ID)

        verify(doorAccessTokenService).revokeByReservation(
            RESERVATION_ID,
            NOW,
            "NO_SHOW",
        )

        verify(reservationStatusHistoryRepository).save(
            argThat { history ->
                history.reservationId.equals(RESERVATION_ID) &&
                    history.changedByMemberId == null &&
                    history.fromStatus == ReservationStatus.CONFIRMED &&
                    history.toStatus == ReservationStatus.NO_SHOW &&
                    history.reason.equals("NO_SHOW") &&
                    history.changedAt.equals(NOW)
            },
        )
    }

    @Test
    @DisplayName("노쇼 상태 전이에 실패하면 출입 토큰을 폐기하지 않는다")
    fun shouldNotRevokeTokenWhenMarkNoShowTransitionFails() {
        given(
            reservationRepository.markNoShowIfNotCheckedIn(
                RESERVATION_ID,
                NOW.minus(ReservationBatchProcessor.CHECK_IN_WINDOW),
                ReservationStatus.CONFIRMED,
                ReservationStatus.NO_SHOW,
            ),
        ).willReturn(0)

        val result: Boolean =
            processor.markNoShow(
                RESERVATION_ID,
                NOW,
            )

        assertThat(result).isFalse()

        verify(reservationSlotRepository, never()).deleteByReservationId(anyNonNull())

        verify(doorAccessTokenService, never()).revokeByReservation(
            anyNonNull(),
            anyNonNull(),
            anyNonNull(),
        )

        verify(reservationStatusHistoryRepository, never()).save(anyNonNull())
    }

    @Test
    @DisplayName("자동 체크아웃에 성공하면 예약 종료 시각을 기준으로 출입 토큰을 폐기한다")
    fun shouldRevokeTokenAtEndTimeWhenAutoCheckOutSucceeds() {
        given(
            reservationRepository.autoCheckOutIfEnded(
                RESERVATION_ID,
                NOW,
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED,
            ),
        ).willReturn(1)

        given(reservationRepository.findById(RESERVATION_ID))
            .willReturn(Optional.of(completedReservation))

        given(completedReservation.getEndTime()).willReturn(END_TIME)

        val result: Boolean =
            processor.autoCheckOut(
                RESERVATION_ID,
                NOW,
            )

        assertThat(result).isTrue()

        verify(doorAccessTokenService).revokeByReservation(
            RESERVATION_ID,
            END_TIME,
            "AUTO_CHECK_OUT",
        )

        verify(reservationStatusHistoryRepository).save(
            argThat { history ->
                history.reservationId.equals(RESERVATION_ID) &&
                    history.changedByMemberId == null &&
                    history.fromStatus == ReservationStatus.IN_USE &&
                    history.toStatus == ReservationStatus.COMPLETED &&
                    history.reason.equals("AUTO_CHECK_OUT") &&
                    history.changedAt.equals(END_TIME)
            },
        )
    }

    @Test
    @DisplayName("자동 체크아웃 상태 전이에 실패하면 출입 토큰을 폐기하지 않는다")
    fun shouldNotRevokeTokenWhenAutoCheckOutTransitionFails() {
        given(
            reservationRepository.autoCheckOutIfEnded(
                RESERVATION_ID,
                NOW,
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED,
            ),
        ).willReturn(0)

        val result: Boolean =
            processor.autoCheckOut(
                RESERVATION_ID,
                NOW,
            )

        assertThat(result).isFalse()

        verify(reservationRepository, never()).findById(anyNonNull())

        verify(doorAccessTokenService, never()).revokeByReservation(
            anyNonNull(),
            anyNonNull(),
            anyNonNull(),
        )

        verify(reservationStatusHistoryRepository, never()).save(anyNonNull())
    }

    companion object {
        private val RESERVATION_ID: Long = 10L
        private val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 17, 15, 0)
        private val END_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 17, 14, 0)
    }
}
