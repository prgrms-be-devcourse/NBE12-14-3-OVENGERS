package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.credit.service.CreditService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.idempotency.IdempotencyService
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.eqNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.argThat
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Optional

/**
 * ReservationPaymentConfirmService(결제 확인의 멱등성·소유권 검증)에 대한 테스트.
 */
@ExtendWith(MockitoExtension::class)
class ReservationPaymentConfirmServiceTest {
    companion object {
        private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val REQUEST_PATH: String = "/reservations/10/pay"
        private val MEMBER_ID: Long = 1L
        private val OTHER_MEMBER_ID: Long = 2L
        private val RESERVATION_ID: Long = 10L
        private val SPACE_ID: Long = 5L
    }

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    @Mock
    private lateinit var spaceRepository: SpaceRepository

    @Mock
    private lateinit var creditService: CreditService

    @Mock
    private lateinit var idempotencyService: IdempotencyService

    private lateinit var clock: Clock
    private lateinit var confirmService: ReservationPaymentConfirmService
    private lateinit var now: LocalDateTime

    @BeforeEach
    fun setUp() {
        now = LocalDateTime.of(2026, 9, 17, 13, 0)
        clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE)
        confirmService =
            ReservationPaymentConfirmService(
                reservationRepository,
                reservationStatusHistoryRepository,
                spaceRepository,
                creditService,
                idempotencyService,
                clock,
            )
    }

    private fun heldReservation(memberId: Long): Reservation =
        Reservation
            .builder()
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
            .build()

    private fun confirmedReservation(): Reservation =
        Reservation
            .builder()
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
            .build()

    private fun spaceWithVersion(version: Int): Space =
        Space
            .builder()
            .id(SPACE_ID)
            .name("컨퍼런스 룸")
            .location("서울")
            .capacity(10)
            .pricePerSlot(5000L)
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(22, 0))
            .status(SpaceStatus.ACTIVE)
            .version(version)
            .build()

    @Test
    @DisplayName("Idempotency-Key가 없거나 공백이면 IDEMPOTENCY_KEY_REQUIRED 예외가 발생하고 다른 처리는 하지 않는다")
    fun confirm_blankKey_throwsException() {
        assertThatThrownBy({ confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, " ") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_REQUIRED)

        verifyNoInteractions(
            idempotencyService,
            reservationRepository,
            spaceRepository,
            creditService,
            reservationStatusHistoryRepository,
        )
    }

    @Test
    @DisplayName("이미 처리된 키로 재요청하면 캐시된 응답을 그대로 반환하고 크레딧을 다시 차감하지 않는다")
    fun confirm_cachedKey_returnsCachedResponseWithoutCharging() {
        val cached: ReservationResponse = ReservationResponse.from(confirmedReservation())
        given(idempotencyService.find("key-1", MEMBER_ID, REQUEST_PATH, ReservationResponse::class.java))
            .willReturn(cached)

        val response: ReservationResponse = confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, "key-1")

        assertThat(response).isEqualTo(cached)
        verifyNoInteractions(reservationRepository, creditService, spaceRepository, reservationStatusHistoryRepository)
        verify(idempotencyService, never()).save(anyNonNull(), anyNonNull(), anyNonNull(), anyInt(), anyNonNull())
    }

    @Test
    @DisplayName("본인이 아닌 예약을 확인 요청하면 공간 버전이 정확하더라도 FORBIDDEN_NOT_OWNER 예외가 발생하고 Space 잠금을 요청하지 않는다")
    fun confirm_notOwner_correctVersion_throwsForbidden_withoutSpaceLock() {
        given(idempotencyService.find("key-1", OTHER_MEMBER_ID, REQUEST_PATH, ReservationResponse::class.java))
            .willReturn(null)
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
            .willReturn(
                com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(
                    SPACE_ID,
                    MEMBER_ID,
                ),
            )

        assertThatThrownBy({ confirmService.confirm(OTHER_MEMBER_ID, RESERVATION_ID, 0, "key-1") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER)

        verifyNoInteractions(spaceRepository, creditService)
    }

    @Test
    @DisplayName("본인이 아닌 예약을 확인 요청하면 잘못된 공간 버전을 보내더라도 FORBIDDEN_NOT_OWNER가 발생하고 Space 잠금을 요청하지 않는다")
    fun confirm_notOwner_wrongVersion_throwsForbidden_withoutSpaceLock() {
        given(idempotencyService.find("key-1", OTHER_MEMBER_ID, REQUEST_PATH, ReservationResponse::class.java))
            .willReturn(null)
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
            .willReturn(
                com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(
                    SPACE_ID,
                    MEMBER_ID,
                ),
            )

        assertThatThrownBy({ confirmService.confirm(OTHER_MEMBER_ID, RESERVATION_ID, 999, "key-1") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER)

        verifyNoInteractions(spaceRepository, creditService)
    }

    @Test
    @DisplayName("결제 대기 중 공간 가격 버전이 바뀌었으면 SPACE_VERSION_MISMATCH 예외가 발생한다")
    fun confirm_spaceVersionMismatch_throwsException() {
        given(idempotencyService.find("key-1", MEMBER_ID, REQUEST_PATH, ReservationResponse::class.java))
            .willReturn(null)
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
            .willReturn(
                com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(
                    SPACE_ID,
                    MEMBER_ID,
                ),
            )
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(spaceWithVersion(1)))

        assertThatThrownBy({ confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, "key-1") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_VERSION_MISMATCH)

        verifyNoInteractions(creditService)
    }

    @Test
    @DisplayName("크레딧 잔액이 부족하면 예외가 전파되고 예약 상태 전이는 시도하지 않는다")
    fun confirm_insufficientBalance_propagatesExceptionWithoutTransition() {
        given(idempotencyService.find("key-1", MEMBER_ID, REQUEST_PATH, ReservationResponse::class.java))
            .willReturn(null)
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
            .willReturn(
                com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(
                    SPACE_ID,
                    MEMBER_ID,
                ),
            )
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(spaceWithVersion(0)))
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(heldReservation(MEMBER_ID)))
        given(creditService.charge(MEMBER_ID, RESERVATION_ID, 10000))
            .willThrow(BusinessException(ErrorCode.INSUFFICIENT_BALANCE))

        assertThatThrownBy({ confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, "key-1") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_BALANCE)

        verify(reservationRepository, never()).confirmIfHeldAndNotExpired(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull())
        verifyNoInteractions(reservationStatusHistoryRepository)
        verify(idempotencyService, never()).save(anyNonNull(), anyNonNull(), anyNonNull(), anyInt(), anyNonNull())
    }

    @Test
    @DisplayName("결제 확인 시점에 HOLD가 이미 만료되었으면 RESERVATION_STATE_CONFLICT 예외가 발생한다")
    fun confirm_holdExpired_throwsException() {
        given(idempotencyService.find("key-1", MEMBER_ID, REQUEST_PATH, ReservationResponse::class.java))
            .willReturn(null)
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
            .willReturn(
                com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(
                    SPACE_ID,
                    MEMBER_ID,
                ),
            )
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(spaceWithVersion(0)))
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(heldReservation(MEMBER_ID)))
        given(creditService.charge(MEMBER_ID, RESERVATION_ID, 10000)).willReturn(80000)
        given(
            reservationRepository.confirmIfHeldAndNotExpired(
                RESERVATION_ID,
                now,
                ReservationStatus.HELD,
                ReservationStatus.CONFIRMED,
            ),
        ).willReturn(0)

        assertThatThrownBy({ confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, "key-1") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

        verifyNoInteractions(reservationStatusHistoryRepository)
        verify(idempotencyService, never()).save(anyNonNull(), anyNonNull(), anyNonNull(), anyInt(), anyNonNull())
    }

    @Test
    @DisplayName("정상 흐름에서는 크레딧을 차감하고 CONFIRMED로 전이한 뒤 이력과 응답을 저장한다")
    fun confirm_success_confirmsReservationAndCachesResponse() {
        val held: Reservation = heldReservation(MEMBER_ID)
        val confirmed: Reservation = confirmedReservation()
        given(idempotencyService.find("key-1", MEMBER_ID, REQUEST_PATH, ReservationResponse::class.java))
            .willReturn(null)
        given(reservationRepository.findTargetInfoById(RESERVATION_ID))
            .willReturn(
                com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(
                    SPACE_ID,
                    MEMBER_ID,
                ),
            )
        given(spaceRepository.findByIdForShare(SPACE_ID)).willReturn(Optional.of(spaceWithVersion(0)))
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(held), Optional.of(confirmed))
        given(creditService.charge(MEMBER_ID, RESERVATION_ID, 10000)).willReturn(90000)
        given(
            reservationRepository.confirmIfHeldAndNotExpired(
                RESERVATION_ID,
                now,
                ReservationStatus.HELD,
                ReservationStatus.CONFIRMED,
            ),
        ).willReturn(1)

        val response: ReservationResponse = confirmService.confirm(MEMBER_ID, RESERVATION_ID, 0, "key-1")

        assertThat(response.status).isEqualTo("CONFIRMED")
        verify(creditService).charge(MEMBER_ID, RESERVATION_ID, 10000)
        verify(reservationStatusHistoryRepository).save(
            argThat({ history ->
                history.fromStatus == ReservationStatus.HELD &&
                    history.toStatus == ReservationStatus.CONFIRMED
            }),
        )
        verify(
            idempotencyService,
        ).save(eqNonNull("key-1"), eqNonNull(MEMBER_ID), eqNonNull(REQUEST_PATH), eqNonNull(200), eqNonNull(response))
    }
}
