package com.ovengers.slotkey.access.service

import com.ovengers.slotkey.access.authorization.DoorAccessAuthorizationService
import com.ovengers.slotkey.access.entity.DoorAccessToken
import com.ovengers.slotkey.access.policy.DoorAccessTimePolicy
import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository
import com.ovengers.slotkey.access.support.AccessTokenGenerator
import com.ovengers.slotkey.access.support.AccessTokenHasher
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class DoorAccessTokenServiceTest {
    @Mock
    private lateinit var doorAccessTokenRepository: DoorAccessTokenRepository

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var doorAccessAuthorizationService: DoorAccessAuthorizationService

    @Mock
    private lateinit var doorAccessTimePolicy: DoorAccessTimePolicy

    @Mock
    private lateinit var accessTokenGenerator: AccessTokenGenerator

    @Mock
    private lateinit var accessTokenHasher: AccessTokenHasher

    @Mock
    private lateinit var reservation: Reservation

    @Mock
    private lateinit var activeToken: DoorAccessToken

    private lateinit var service: DoorAccessTokenService

    @BeforeEach
    fun setUp() {
        val clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        service =
            DoorAccessTokenService(
                doorAccessTokenRepository = doorAccessTokenRepository,
                reservationRepository = reservationRepository,
                doorAccessAuthorizationService = doorAccessAuthorizationService,
                doorAccessTimePolicy = doorAccessTimePolicy,
                accessTokenGenerator = accessTokenGenerator,
                accessTokenHasher = accessTokenHasher,
                clock = clock,
            )
    }

    @Test
    @DisplayName("확정된 예약에 출입 토큰을 발급한다")
    fun shouldIssueTokenForConfirmedReservation() {
        val endAt = NOW.plusHours(1)

        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(reservation))
        given(reservation.getMemberId()).willReturn(MEMBER_ID)
        given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED)
        given(reservation.getEndTime()).willReturn(endAt)
        given(doorAccessTimePolicy.canIssueToken(NOW, endAt)).willReturn(true)
        given(doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNullForUpdate(RESERVATION_ID))
            .willReturn(Optional.empty())
        given(accessTokenGenerator.generate()).willReturn(RAW_TOKEN)
        given(accessTokenHasher.hash(RAW_TOKEN)).willReturn(TOKEN_HASH)
        given(doorAccessTokenRepository.save(tokenOrPlaceholder(any(DoorAccessToken::class.java))))
            .willAnswer { invocation -> invocation.getArgument<DoorAccessToken>(0) }

        val response = service.issue(MEMBER_ID, RESERVATION_ID)

        assertThat(response.reservationId).isEqualTo(RESERVATION_ID)
        assertThat(response.token).isEqualTo(RAW_TOKEN)
        assertThat(response.issuedAt).isEqualTo(NOW)

        val tokenCaptor = ArgumentCaptor.forClass(DoorAccessToken::class.java)
        verify(doorAccessTokenRepository).save(tokenOrPlaceholder(tokenCaptor.capture()))
        val savedToken = tokenCaptor.value

        assertThat(savedToken.reservation).isEqualTo(reservation)
        assertThat(savedToken.tokenHash).isEqualTo(TOKEN_HASH)
        assertThat(savedToken.issuedAt).isEqualTo(NOW)
        verify(doorAccessAuthorizationService).validateOwner(MEMBER_ID, MEMBER_ID)
    }

    @Test
    @DisplayName("기존에 활성화된 출입 토큰이 있으면 폐기하고 재발급한다.")
    fun shouldRevokeActiveTokenWhenReissuing() {
        val endAt = NOW.plusHours(1)

        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(reservation))
        given(reservation.getMemberId()).willReturn(MEMBER_ID)
        given(reservation.getStatus()).willReturn(ReservationStatus.CONFIRMED)
        given(reservation.getEndTime()).willReturn(endAt)
        given(doorAccessTimePolicy.canIssueToken(NOW, endAt)).willReturn(true)
        given(doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNullForUpdate(RESERVATION_ID))
            .willReturn(Optional.of(activeToken))
        given(accessTokenGenerator.generate()).willReturn(RAW_TOKEN)
        given(accessTokenHasher.hash(RAW_TOKEN)).willReturn(TOKEN_HASH)

        service.issue(MEMBER_ID, RESERVATION_ID)

        verify(activeToken).revoke(NOW, "REISSUED")
        verify(doorAccessTokenRepository).save(tokenOrPlaceholder(any(DoorAccessToken::class.java)))
    }

    @Test
    @DisplayName("확정되지 않은 예약에는 출입 토큰을 발급할 수 없다")
    fun shouldRejectIssueWhenReservationIsNotConfirmed() {
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(reservation))
        given(reservation.getMemberId()).willReturn(MEMBER_ID)
        given(reservation.getStatus()).willReturn(ReservationStatus.HELD)

        assertThatThrownBy { service.issue(MEMBER_ID, RESERVATION_ID) }
            .isInstanceOfSatisfying(BusinessException::class.java) { exception ->
                assertThat(exception.errorCode).isEqualTo(ErrorCode.RESERVATION_STATE_CONFLICT)
            }
    }

    @Test
    @DisplayName("원문 토큰을 해시한 값으로 출입 토큰을 조회한다")
    fun shouldFindTokenUsingHashedRawToken() {
        given(accessTokenHasher.hash(RAW_TOKEN)).willReturn(TOKEN_HASH)
        given(doorAccessTokenRepository.findByTokenHash(TOKEN_HASH)).willReturn(Optional.of(activeToken))

        val result = service.findByRawToken(RAW_TOKEN)

        assertThat(result).isEqualTo(activeToken)
        verify(accessTokenHasher).hash(RAW_TOKEN)
        verify(doorAccessTokenRepository).findByTokenHash(TOKEN_HASH)
    }

    @Test
    @DisplayName("예약 소유자는 활성 출입 토큰을 폐기할 수 있다")
    fun shouldRevokeActiveTokenByReservationOwner() {
        given(reservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(reservation))
        given(reservation.getMemberId()).willReturn(MEMBER_ID)
        given(doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNullForUpdate(RESERVATION_ID))
            .willReturn(Optional.of(activeToken))

        service.revokeByReservationId(MEMBER_ID, RESERVATION_ID, NOW, "사용자 요청")

        verify(doorAccessAuthorizationService).validateOwner(MEMBER_ID, MEMBER_ID)
        verify(activeToken).revoke(NOW, "사용자 요청")
    }

    // Mockito matcher·captor는 null을 반환하므로 등록 후 Kotlin non-null 인자에 넣을 대체 값만 제공한다.
    private fun tokenOrPlaceholder(token: DoorAccessToken?): DoorAccessToken =
        token ?: DoorAccessToken(reservation = null, tokenHash = null, issuedAt = null)

    private companion object {
        private const val MEMBER_ID = 1L
        private const val RESERVATION_ID = 10L
        private const val RAW_TOKEN = "raw-token"
        private const val TOKEN_HASH = "hashed-token"
        private val NOW = LocalDateTime.of(2026, 9, 17, 10, 0)
    }
}
