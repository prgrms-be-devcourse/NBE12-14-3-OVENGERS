package com.ovengers.slotkey.access.service

import com.ovengers.slotkey.access.dto.request.DoorAccessVerifyRequest
import com.ovengers.slotkey.access.dto.response.DoorAccessVerifyResponse
import com.ovengers.slotkey.access.entity.AccessDenyReason
import com.ovengers.slotkey.access.entity.AccessResult
import com.ovengers.slotkey.access.entity.DoorAccessToken
import com.ovengers.slotkey.access.policy.DoorAccessTimePolicy
import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.repository.SpaceRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class DoorAccessVerificationServiceTest {
    @Mock
    private lateinit var doorAccessTokenService: DoorAccessTokenService

    @Mock
    private lateinit var doorAccessLogService: DoorAccessLogService

    @Mock
    private lateinit var doorAccessTimePolicy: DoorAccessTimePolicy

    @Mock
    private lateinit var memberRepository: MemberRepository

    @Mock
    private lateinit var doorAccessTokenRepository: DoorAccessTokenRepository

    @Mock
    private lateinit var spaceRepository: SpaceRepository

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    @Mock
    private lateinit var entityManager: EntityManager

    @Mock
    private lateinit var request: DoorAccessVerifyRequest

    @Mock
    private lateinit var accessToken: DoorAccessToken

    @Mock
    private lateinit var reservation: Reservation

    @Mock
    private lateinit var actorMember: Member

    @Mock
    private lateinit var requestedSpace: Space

    private lateinit var service: DoorAccessVerificationService

    @BeforeEach
    fun setUp() {
        val clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        service =
            DoorAccessVerificationService(
                doorAccessTokenService = doorAccessTokenService,
                doorAccessTokenRepository = doorAccessTokenRepository,
                doorAccessLogService = doorAccessLogService,
                doorAccessTimePolicy = doorAccessTimePolicy,
                memberRepository = memberRepository,
                spaceRepository = spaceRepository,
                clock = clock,
                reservationRepository = reservationRepository,
                reservationStatusHistoryRepository = reservationStatusHistoryRepository,
                entityManager = entityManager,
            )
    }

    @Test
    @DisplayName("존재하지 않는 출입 토큰이면 출입을 거절한다")
    fun shouldDenyWhenTokenDoesNotExist() {
        givenRequestContext()
        given(doorAccessTokenService.findByRawTokenOrNull(RAW_TOKEN)).willReturn(null)

        val response = service.verify(MEMBER_ID, request)

        assertDenied(response, AccessDenyReason.TOKEN_NOT_FOUND)
        verify(doorAccessLogService).createDenyLog(actorMember, null, requestedSpace, AccessDenyReason.TOKEN_NOT_FOUND, NOW)
    }

    @Test
    @DisplayName("로그인 회원과 예약 회원이 다르면 출입을 거절한다")
    fun shouldDenyWhenMemberDoesNotMatch() {
        givenRequestContext()
        givenTokenAndReservation()
        given(reservation.getMemberId()).willReturn(OTHER_MEMBER_ID)

        val response = service.verify(MEMBER_ID, request)

        assertDenied(response, AccessDenyReason.MEMBER_MISMATCH)
        verify(doorAccessLogService).createDenyLog(actorMember, reservation, requestedSpace, AccessDenyReason.MEMBER_MISMATCH, NOW)
    }

    @Test
    @DisplayName("폐기된 출입 토큰이면 출입을 거절한다")
    fun shouldDenyWhenTokenIsRevoked() {
        givenRequestContext()
        givenOwnedTokenAndReservation()
        given(accessToken.isRevoked).willReturn(true)

        val response = service.verify(MEMBER_ID, request)

        assertDenied(response, AccessDenyReason.TOKEN_REVOKED)
        verify(doorAccessLogService).createDenyLog(actorMember, reservation, requestedSpace, AccessDenyReason.TOKEN_REVOKED, NOW)
    }

    @Test
    @DisplayName("출입 가능한 예약 상태가 아니면 출입을 거절한다")
    fun shouldDenyWhenReservationIsNotActive() {
        givenRequestContext()
        givenOwnedTokenAndReservation()
        given(reservation.getStatus()).willReturn(ReservationStatus.HELD)

        val response = service.verify(MEMBER_ID, request)

        assertDenied(response, AccessDenyReason.RESERVATION_NOT_ACTIVE)
        verify(doorAccessLogService).createDenyLog(actorMember, reservation, requestedSpace, AccessDenyReason.RESERVATION_NOT_ACTIVE, NOW)
    }

    @Test
    @DisplayName("요청 공간과 예약 공간이 다르면 출입을 거절한다")
    fun shouldDenyWhenSpaceDoesNotMatch() {
        givenRequestContext()
        givenOwnedTokenAndReservation()
        given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED)
        given(reservation.getSpaceId()).willReturn(OTHER_SPACE_ID)

        val response = service.verify(MEMBER_ID, request)

        assertDenied(response, AccessDenyReason.SPACE_MISMATCH)
        verify(doorAccessLogService).createDenyLog(actorMember, reservation, requestedSpace, AccessDenyReason.SPACE_MISMATCH, NOW)
    }

    @Test
    @DisplayName("출입 가능한 시간이 아니면 출입을 거절한다")
    fun shouldDenyWhenOutsideAllowedTime() {
        givenRequestContext()
        givenActiveReservation()
        given(doorAccessTimePolicy.findDenyReason(NOW, START_AT, END_AT, null)).willReturn(AccessDenyReason.OUTSIDE_ALLOWED_TIME)

        val response = service.verify(MEMBER_ID, request)

        assertDenied(response, AccessDenyReason.OUTSIDE_ALLOWED_TIME)
        verify(doorAccessLogService).createDenyLog(actorMember, reservation, requestedSpace, AccessDenyReason.OUTSIDE_ALLOWED_TIME, NOW)
    }

    @Test
    @DisplayName("모든 출입 조건을 만족하면 출입을 허용하고 최초 체크인한다")
    fun shouldAllowAccessAndCheckIn() {
        givenRequestContext()
        givenActiveReservation()
        given(reservation.getId()).willReturn(RESERVATION_ID)
        given(accessToken.id).willReturn(100L)
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(reservation))
        given(doorAccessTokenRepository.findByIdForUpdate(100L)).willReturn(Optional.of(accessToken))
        given(accessToken.isRevoked).willReturn(false)
        given(
            reservationRepository.checkInIfConfirmed(
                RESERVATION_ID,
                NOW,
                ReservationStatus.CONFIRMED,
                ReservationStatus.IN_USE,
            ),
        ).willReturn(1)
        given(reservationRepository.findById(RESERVATION_ID)).willReturn(Optional.of(reservation))
        given(doorAccessTimePolicy.findDenyReason(NOW, START_AT, END_AT, null)).willReturn(null)

        val response = service.verify(MEMBER_ID, request)

        assertThat(response.result).isEqualTo(AccessResult.ALLOW)
        assertThat(response.reasonCode).isNull()
        assertThat(response.attemptedAt).isEqualTo(NOW)
        verify(entityManager).detach(reservation)
        verify(entityManager).detach(accessToken)
        verify(reservationRepository).checkInIfConfirmed(
            RESERVATION_ID,
            NOW,
            ReservationStatus.CONFIRMED,
            ReservationStatus.IN_USE,
        )
        verify(reservationStatusHistoryRepository).save(
            historyOrPlaceholder(
                argThat<ReservationStatusHistory> { history ->
                    history.reservationId == RESERVATION_ID &&
                        history.changedByMemberId == MEMBER_ID &&
                        history.fromStatus == ReservationStatus.CONFIRMED &&
                        history.toStatus == ReservationStatus.IN_USE &&
                        history.reason == "FIRST_CHECK_IN" &&
                        history.changedAt == NOW
                },
            ),
        )
        verify(doorAccessLogService).createAllowLog(actorMember, reservation, requestedSpace, NOW)
    }

    @Test
    @DisplayName("최초 체크인 직전 locking read 시점에 동시 강제 취소 등으로 상태가 이미 비활성화되었으면 출입을 거절한다")
    fun shouldDenyWhenFirstCheckInFailsDueToConcurrentConflict() {
        givenRequestContext()
        givenActiveReservation()
        given(reservation.getId()).willReturn(RESERVATION_ID)
        val cancelledReservation = mock(Reservation::class.java)
        given(cancelledReservation.getStatus()).willReturn(ReservationStatus.CANCELLED)
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(cancelledReservation))
        given(doorAccessTimePolicy.findDenyReason(NOW, START_AT, END_AT, null)).willReturn(null)

        val response = service.verify(MEMBER_ID, request)

        assertDenied(response, AccessDenyReason.RESERVATION_NOT_ACTIVE)
        verify(entityManager).detach(reservation)
        verify(entityManager).detach(accessToken)
        verify(reservationRepository, never()).checkInIfConfirmed(
            any(),
            any<LocalDateTime>() ?: NOW,
            any<ReservationStatus>() ?: ReservationStatus.CONFIRMED,
            any<ReservationStatus>() ?: ReservationStatus.IN_USE,
        )
        verify(reservationStatusHistoryRepository, never()).save(historyOrPlaceholder(any()))
        verify(doorAccessLogService).createDenyLog(
            actorMember,
            cancelledReservation,
            requestedSpace,
            AccessDenyReason.RESERVATION_NOT_ACTIVE,
            NOW,
        )
    }

    @Test
    @DisplayName("최종 허가 전 예약 locking read 시 예약이 사라진 경우 출입을 거절하고 fail-closed 처리한다")
    fun shouldDenyWhenReservationDisappearsAfterCheckInUpdate() {
        givenRequestContext()
        givenActiveReservation()
        given(reservation.getId()).willReturn(RESERVATION_ID)
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.empty())
        given(doorAccessTimePolicy.findDenyReason(NOW, START_AT, END_AT, null)).willReturn(null)

        val response = service.verify(MEMBER_ID, request)

        assertDenied(response, AccessDenyReason.RESERVATION_NOT_ACTIVE)
        verify(entityManager).detach(reservation)
        verify(entityManager).detach(accessToken)
        verify(reservationRepository, never()).checkInIfConfirmed(
            any(),
            any<LocalDateTime>() ?: NOW,
            any<ReservationStatus>() ?: ReservationStatus.CONFIRMED,
            any<ReservationStatus>() ?: ReservationStatus.IN_USE,
        )
        verify(doorAccessLogService).createDenyLog(actorMember, null, requestedSpace, AccessDenyReason.RESERVATION_NOT_ACTIVE, NOW)
    }

    @Test
    @DisplayName("최종 허가 전 locking read 시 최신 예약이 활성이지만 토큰이 폐기된 경우 TOKEN_REVOKED로 거절한다")
    fun shouldDenyWithTokenRevokedWhenReservationInUseButTokenRevokedAfterCheckInUpdate() {
        givenRequestContext()
        givenActiveReservation()
        given(reservation.getId()).willReturn(RESERVATION_ID)
        given(accessToken.id).willReturn(100L)
        val inUseReservation = mock(Reservation::class.java)
        given(inUseReservation.getStatus()).willReturn(ReservationStatus.IN_USE)
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(inUseReservation))
        val revokedToken = mock(DoorAccessToken::class.java)
        given(revokedToken.isRevoked).willReturn(true)
        given(doorAccessTokenRepository.findByIdForUpdate(100L)).willReturn(Optional.of(revokedToken))
        given(doorAccessTimePolicy.findDenyReason(NOW, START_AT, END_AT, null)).willReturn(null)

        val response = service.verify(MEMBER_ID, request)

        assertDenied(response, AccessDenyReason.TOKEN_REVOKED)
        verify(entityManager).detach(reservation)
        verify(entityManager).detach(accessToken)
        verify(reservationRepository, never()).checkInIfConfirmed(
            any(),
            any<LocalDateTime>() ?: NOW,
            any<ReservationStatus>() ?: ReservationStatus.CONFIRMED,
            any<ReservationStatus>() ?: ReservationStatus.IN_USE,
        )
        verify(reservationStatusHistoryRepository, never()).save(historyOrPlaceholder(any()))
        verify(doorAccessLogService).createDenyLog(actorMember, inUseReservation, requestedSpace, AccessDenyReason.TOKEN_REVOKED, NOW)
    }

    @Test
    @DisplayName("최신 예약이 IN_USE이고 토큰이 활성이면 재입장으로 출입을 허용한다")
    fun shouldAllowWhenAlreadyCheckedInByConcurrentRequestAndTokenActive() {
        givenRequestContext()
        givenActiveReservation()
        given(reservation.getId()).willReturn(RESERVATION_ID)
        given(accessToken.id).willReturn(100L)
        val inUseReservation = mock(Reservation::class.java)
        given(inUseReservation.getStatus()).willReturn(ReservationStatus.IN_USE)
        given(inUseReservation.getSpaceId()).willReturn(SPACE_ID)
        given(inUseReservation.getStartTime()).willReturn(START_AT)
        given(inUseReservation.getEndTime()).willReturn(END_AT)
        given(inUseReservation.checkedInAt).willReturn(NOW.minusMinutes(10))
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(inUseReservation))
        val activeToken = mock(DoorAccessToken::class.java)
        given(activeToken.isRevoked).willReturn(false)
        given(doorAccessTokenRepository.findByIdForUpdate(100L)).willReturn(Optional.of(activeToken))
        given(doorAccessTimePolicy.findDenyReason(NOW, START_AT, END_AT, null)).willReturn(null)

        val response = service.verify(MEMBER_ID, request)

        assertThat(response.result).isEqualTo(AccessResult.ALLOW)
        assertThat(response.reasonCode).isNull()
        assertThat(response.firstCheckIn).isFalse()
        verify(entityManager).detach(reservation)
        verify(entityManager).detach(accessToken)
        verify(reservationRepository, never()).checkInIfConfirmed(
            any(),
            any<LocalDateTime>() ?: NOW,
            any<ReservationStatus>() ?: ReservationStatus.CONFIRMED,
            any<ReservationStatus>() ?: ReservationStatus.IN_USE,
        )
        verify(reservationStatusHistoryRepository, never()).save(historyOrPlaceholder(any()))
        verify(doorAccessLogService).createAllowLog(actorMember, inUseReservation, requestedSpace, NOW)
    }

    @Test
    @DisplayName("재입장 시점에 최신 예약이 취소 상태로 변경되어 있으면 출입을 거절한다")
    fun shouldDenyReentryWhenReservationCancelledConcurrentlyBeforeLockingRead() {
        givenRequestContext()
        givenActiveReservation()
        given(reservation.getStatus()).willReturn(ReservationStatus.IN_USE)
        given(reservation.getId()).willReturn(RESERVATION_ID)
        val cancelledReservation = mock(Reservation::class.java)
        given(cancelledReservation.getStatus()).willReturn(ReservationStatus.CANCELLED)
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(cancelledReservation))
        given(doorAccessTimePolicy.findDenyReason(NOW, START_AT, END_AT, null)).willReturn(null)

        val response = service.verify(MEMBER_ID, request)

        assertDenied(response, AccessDenyReason.RESERVATION_NOT_ACTIVE)
        verify(entityManager).detach(reservation)
        verify(entityManager).detach(accessToken)
        verify(reservationRepository, never()).checkInIfConfirmed(
            any(),
            any<LocalDateTime>() ?: NOW,
            any<ReservationStatus>() ?: ReservationStatus.CONFIRMED,
            any<ReservationStatus>() ?: ReservationStatus.IN_USE,
        )
        verify(reservationStatusHistoryRepository, never()).save(historyOrPlaceholder(any()))
        verify(doorAccessLogService).createDenyLog(
            actorMember,
            cancelledReservation,
            requestedSpace,
            AccessDenyReason.RESERVATION_NOT_ACTIVE,
            NOW,
        )
    }

    @Test
    @DisplayName("재입장 시점에 최신 토큰이 폐기 상태로 변경되어 있으면 TOKEN_REVOKED로 거절한다")
    fun shouldDenyReentryWhenTokenRevokedConcurrentlyBeforeLockingRead() {
        givenRequestContext()
        givenActiveReservation()
        given(reservation.getStatus()).willReturn(ReservationStatus.IN_USE)
        given(reservation.getId()).willReturn(RESERVATION_ID)
        given(accessToken.id).willReturn(100L)
        val inUseReservation = mock(Reservation::class.java)
        given(inUseReservation.getStatus()).willReturn(ReservationStatus.IN_USE)
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(inUseReservation))
        val revokedToken = mock(DoorAccessToken::class.java)
        given(revokedToken.isRevoked).willReturn(true)
        given(doorAccessTokenRepository.findByIdForUpdate(100L)).willReturn(Optional.of(revokedToken))
        given(doorAccessTimePolicy.findDenyReason(NOW, START_AT, END_AT, null)).willReturn(null)

        val response = service.verify(MEMBER_ID, request)

        assertDenied(response, AccessDenyReason.TOKEN_REVOKED)
        verify(entityManager).detach(reservation)
        verify(entityManager).detach(accessToken)
        verify(reservationRepository, never()).checkInIfConfirmed(
            any(),
            any<LocalDateTime>() ?: NOW,
            any<ReservationStatus>() ?: ReservationStatus.CONFIRMED,
            any<ReservationStatus>() ?: ReservationStatus.IN_USE,
        )
        verify(reservationStatusHistoryRepository, never()).save(historyOrPlaceholder(any()))
        verify(doorAccessLogService).createDenyLog(actorMember, inUseReservation, requestedSpace, AccessDenyReason.TOKEN_REVOKED, NOW)
    }

    private fun givenRequestContext() {
        given(request.spaceId).willReturn(SPACE_ID)
        given(request.token).willReturn(RAW_TOKEN)
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(actorMember))
        given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.of(requestedSpace))
    }

    private fun givenTokenAndReservation() {
        given(doorAccessTokenService.findByRawTokenOrNull(RAW_TOKEN)).willReturn(accessToken)
        given(accessToken.reservation).willReturn(reservation)
    }

    private fun givenOwnedTokenAndReservation() {
        givenTokenAndReservation()
        given(reservation.getMemberId()).willReturn(MEMBER_ID)
    }

    private fun givenActiveReservation() {
        givenOwnedTokenAndReservation()
        given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED)
        given(reservation.getSpaceId()).willReturn(SPACE_ID)
        given(reservation.getStartTime()).willReturn(START_AT)
        given(reservation.getEndTime()).willReturn(END_AT)
        given(reservation.checkedInAt).willReturn(null)
    }

    private fun assertDenied(
        response: DoorAccessVerifyResponse,
        reasonCode: AccessDenyReason,
    ) {
        assertThat(response.result).isEqualTo(AccessResult.DENY)
        assertThat(response.reasonCode).isEqualTo(reasonCode)
        assertThat(response.attemptedAt).isEqualTo(NOW)
    }

    // Mockito matcher가 반환한 null만 대체하며 등록된 any/argThat 조건은 그대로 유지한다.
    private fun historyOrPlaceholder(history: ReservationStatusHistory?): ReservationStatusHistory = history ?: ReservationStatusHistory()

    private companion object {
        private const val MEMBER_ID = 1L
        private const val OTHER_MEMBER_ID = 2L
        private const val RESERVATION_ID = 3L
        private const val SPACE_ID = 10L
        private const val OTHER_SPACE_ID = 20L
        private const val RAW_TOKEN = "raw-token"
        private val NOW = LocalDateTime.of(2026, 9, 17, 10, 0)
        private val START_AT = LocalDateTime.of(2026, 9, 17, 10, 0)
        private val END_AT = LocalDateTime.of(2026, 9, 17, 11, 0)
    }
}
