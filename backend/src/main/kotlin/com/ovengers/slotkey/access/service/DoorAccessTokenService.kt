package com.ovengers.slotkey.access.service

import com.ovengers.slotkey.access.authorization.DoorAccessAuthorizationService
import com.ovengers.slotkey.access.dto.response.DoorAccessTokenResponse
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrNull

@Service
@Transactional(readOnly = true)
class DoorAccessTokenService(
    private val doorAccessTokenRepository: DoorAccessTokenRepository,
    private val reservationRepository: ReservationRepository,
    private val doorAccessAuthorizationService: DoorAccessAuthorizationService,
    private val doorAccessTimePolicy: DoorAccessTimePolicy,
    private val accessTokenGenerator: AccessTokenGenerator,
    private val accessTokenHasher: AccessTokenHasher,
    private val clock: Clock,
) {
    // 새로운 출입 토큰 생성 (issue 내부 전용)
    private fun create(
        reservation: Reservation,
        tokenHash: String,
        issuedAt: LocalDateTime,
    ) {
        doorAccessTokenRepository.save(
            DoorAccessToken(
                reservation = reservation,
                tokenHash = tokenHash,
                issuedAt = issuedAt,
            ),
        )
    }

    // 예약에 사용할 출입 토큰 발급
    @Transactional
    fun issue(
        loginMemberId: Long?,
        reservationId: Long?,
    ): DoorAccessTokenResponse {
        val reservation =
            reservationRepository.findByIdForUpdate(reservationId).getOrNull()
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)

        // Reservation의 프록시 초기화를 지원하는 기존 공개 getter를 유지한다.
        // TODO: 예약 엔티티의 프로퍼티가 프록시 초기화를 지원하면 프로퍼티 접근으로 변경한다.
        doorAccessAuthorizationService.validateOwner(loginMemberId, reservation.getMemberId())

        // 확정 또는 이용 중인 예약인지 확인
        val status = reservation.getStatus()
        if (status != ReservationStatus.CONFIRMED && status != ReservationStatus.IN_USE) {
            throw BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT)
        }

        val issuedAt = LocalDateTime.now(clock)

        // 예약 종료 전인지 확인
        if (!doorAccessTimePolicy.canIssueToken(issuedAt, reservation.getEndTime())) {
            throw BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT)
        }

        // Reservation -> DoorAccessToken 잠금 순서에 따라 활성 토큰 비관적 락 조회 후 폐기
        doorAccessTokenRepository
            .findByReservationIdAndRevokedAtIsNullForUpdate(reservationId)
            .getOrNull()
            ?.revoke(issuedAt, "REISSUED")
        doorAccessTokenRepository.flush()

        // 새로운 원문 토큰을 생성하고 SHA-256으로 해시
        val rawToken = accessTokenGenerator.generate()
        val tokenHash = accessTokenHasher.hash(rawToken)

        // DB에는 해시된 토큰 저장. 동시 발급은 활성 토큰 UNIQUE 제약(active_reservation_id)으로 제한한다.
        // 저장 시 충돌한 요청은 500 대신 409로 돌려준다.
        try {
            create(
                reservation = reservation,
                tokenHash = tokenHash,
                issuedAt = issuedAt,
            )
        } catch (exception: DataIntegrityViolationException) {
            throw BusinessException(
                ErrorCode.RESERVATION_STATE_CONFLICT,
                "동시에 다른 발급 요청이 처리되었습니다. 다시 시도해주세요.",
            )
        }

        // 사용자에게는 원문 토큰 반환
        return DoorAccessTokenResponse.of(
            reservationId = reservationId,
            token = rawToken,
            issuedAt = issuedAt,
        )
    }

    // 출입 토큰 단건 조회
    fun findById(tokenId: Long?): DoorAccessToken =
        doorAccessTokenRepository.findByIdOrNull(requireNotNull(tokenId) { "The given id must not be null" })
            ?: throw BusinessException(ErrorCode.ACCESS_TOKEN_NOT_FOUND)

    // 토큰 해시로 단건 조회
    fun findByTokenHash(tokenHash: String?): DoorAccessToken =
        doorAccessTokenRepository.findByTokenHash(tokenHash).getOrNull()
            ?: throw BusinessException(ErrorCode.ACCESS_TOKEN_NOT_FOUND)

    // 원문 토큰으로 단건 조회
    fun findByRawToken(rawToken: String?): DoorAccessToken = findByTokenHash(accessTokenHasher.hash(rawToken))

    // 검증 실패를 정상적인 거절 결과로 처리할 수 있도록 없으면 null 반환
    fun findByRawTokenOrNull(rawToken: String?): DoorAccessToken? =
        doorAccessTokenRepository.findByTokenHash(accessTokenHasher.hash(rawToken)).getOrNull()

    // 특정 예약의 활성 토큰 조회
    fun findActiveByReservationId(reservationId: Long?): DoorAccessToken =
        doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).getOrNull()
            ?: throw BusinessException(ErrorCode.ACTIVE_ACCESS_TOKEN_NOT_FOUND)

    // 출입 토큰 전체 조회
    fun findAll(): List<DoorAccessToken> = doorAccessTokenRepository.findAll()

    // 기존 패키지 전용 폐기는 Kotlin 모듈 내부로 제한하고 Java 소스 호출에서는 숨긴다.
    @JvmSynthetic
    @Transactional
    internal fun revoke(
        tokenId: Long?,
        revokedAt: LocalDateTime?,
        revokeReason: String?,
    ): DoorAccessToken =
        findById(tokenId).apply {
            revoke(revokedAt, revokeReason)
        }

    // 예약 소유자가 활성 출입 토큰 폐기
    @Transactional
    fun revokeByReservationId(
        loginMemberId: Long?,
        reservationId: Long?,
        revokedAt: LocalDateTime?,
        revokeReason: String?,
    ) {
        val reservation =
            reservationRepository.findByIdForUpdate(reservationId).getOrNull()
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)

        doorAccessAuthorizationService.validateOwner(loginMemberId, reservation.getMemberId())

        val token =
            doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNullForUpdate(reservationId).getOrNull()
                ?: throw BusinessException(ErrorCode.ACTIVE_ACCESS_TOKEN_NOT_FOUND)

        token.revoke(revokedAt, revokeReason)
    }

    /**
     * 예약 상태 변경에 따른 활성 출입 토큰 폐기 (시스템 내부 경로).
     *
     * 호출자(AdminReservationService.forceCancel, ReservationBatchProcessor 등)가 동일 트랜잭션에서
     * Reservation 상태 전이(조건부 UPDATE)의 행 배타적 잠금(X-Lock)을 먼저 획득해야 한다.
     * 이를 통해 Reservation -> DoorAccessToken 잠금 순서를 일관되게 보장한다.
     */
    @Transactional
    fun revokeByReservation(
        reservationId: Long?,
        revokedAt: LocalDateTime?,
        revokeReason: String?,
    ) {
        doorAccessTokenRepository
            .findByReservationIdAndRevokedAtIsNullForUpdate(reservationId)
            .getOrNull()
            ?.revoke(revokedAt, revokeReason)
    }
}
