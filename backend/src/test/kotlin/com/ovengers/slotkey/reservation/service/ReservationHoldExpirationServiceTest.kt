package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.outbox.SlotReleasedRecorder
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
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
class ReservationHoldExpirationServiceTest {
    companion object {
        private val RESERVATION_ID: Long = 100L
        private val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 20, 14, 0, 0)
    }

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var reservationSlotRepository: ReservationSlotRepository

    @Mock
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    private lateinit var expirationService: ReservationHoldExpirationService

    @Mock
    private lateinit var slotReleasedRecorder: SlotReleasedRecorder

    @BeforeEach
    fun setUp() {
        expirationService =
            ReservationHoldExpirationService(
                reservationRepository,
                reservationSlotRepository,
                reservationStatusHistoryRepository,
                slotReleasedRecorder,
            )
        org.mockito.Mockito
            .lenient()
            .`when`(reservationRepository.findById(anyNonNull()))
            .thenReturn(Optional.of(Reservation.builder().spaceId(5L).build()))
    }

    @Test
    @DisplayName("조건부 UPDATE가 1이면 슬롯을 삭제하고 HOLD_EXPIRED 이력을 저장한 뒤 true를 반환한다")
    fun expireHold_whenUpdateSucceeds_deletesSlotsAndSavesHistoryAndReturnsTrue() {
        // given
        given(
            reservationRepository.expireHeldReservations(
                listOf(RESERVATION_ID),
                NOW,
                ReservationStatus.HELD,
                ReservationStatus.EXPIRED,
            ),
        ).willReturn(1)

        // when
        val result: Boolean = expirationService.expireHold(RESERVATION_ID, NOW)

        // then
        assertThat(result).isTrue()

        verify(reservationRepository).expireHeldReservations(
            listOf(RESERVATION_ID),
            NOW,
            ReservationStatus.HELD,
            ReservationStatus.EXPIRED,
        )

        verify(reservationSlotRepository).deleteSlotsOfExpiredReservations(
            listOf(RESERVATION_ID),
            ReservationStatus.EXPIRED,
        )

        verify(reservationStatusHistoryRepository)
            .save(
                argThat({ history ->
                    history.reservationId.equals(RESERVATION_ID) &&
                        history.changedByMemberId == null &&
                        history.fromStatus == ReservationStatus.HELD &&
                        history.toStatus == ReservationStatus.EXPIRED &&
                        "HOLD_EXPIRED".equals(history.reason) &&
                        history.changedAt.equals(NOW)
                }),
            )

        verify(slotReleasedRecorder).record(RESERVATION_ID, 5L, SlotReleasedRecorder.Reason.HOLD_EXPIRED)
    }

    @Test
    @DisplayName("조건부 UPDATE가 0이면 방어적 슬롯 정리 쿼리는 실행하되 이력 저장은 수행하지 않고 false를 반환한다")
    fun expireHold_whenUpdateFails_cleansUpSlotsDefensivelyWithoutSavingHistoryAndReturnsFalse() {
        // given
        given(
            reservationRepository.expireHeldReservations(
                listOf(RESERVATION_ID),
                NOW,
                ReservationStatus.HELD,
                ReservationStatus.EXPIRED,
            ),
        ).willReturn(0)

        // when
        val result: Boolean = expirationService.expireHold(RESERVATION_ID, NOW)

        // then
        assertThat(result).isFalse()

        verify(reservationRepository).expireHeldReservations(
            listOf(RESERVATION_ID),
            NOW,
            ReservationStatus.HELD,
            ReservationStatus.EXPIRED,
        )

        verify(reservationSlotRepository).deleteSlotsOfExpiredReservations(
            listOf(RESERVATION_ID),
            ReservationStatus.EXPIRED,
        )

        verify(reservationStatusHistoryRepository, never()).save(anyNonNull())
        verify(slotReleasedRecorder, never()).record(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyNonNull(),
        )
    }

    @Test
    @DisplayName("모든 저장소 호출에 호출자가 전달한 동일한 reservationId와 now가 전달된다")
    fun expireHold_passesSameParametersAcrossAllCalls() {
        // given
        val targetId: Long = 200L
        val testNow: LocalDateTime = LocalDateTime.of(2026, 9, 20, 15, 30, 0)

        given(
            reservationRepository.expireHeldReservations(
                listOf(targetId),
                testNow,
                ReservationStatus.HELD,
                ReservationStatus.EXPIRED,
            ),
        ).willReturn(1)

        // when
        expirationService.expireHold(targetId, testNow)

        // then
        verify(reservationRepository).expireHeldReservations(
            listOf(targetId),
            testNow,
            ReservationStatus.HELD,
            ReservationStatus.EXPIRED,
        )

        verify(reservationSlotRepository).deleteSlotsOfExpiredReservations(
            listOf(targetId),
            ReservationStatus.EXPIRED,
        )

        verify(reservationStatusHistoryRepository).save(
            argThat(
                { h -> h.reservationId.equals(targetId) && h.changedAt.equals(testNow) },
            ),
        )
    }
}
