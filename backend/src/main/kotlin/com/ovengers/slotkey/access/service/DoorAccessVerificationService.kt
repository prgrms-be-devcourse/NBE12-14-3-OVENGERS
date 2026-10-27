package com.ovengers.slotkey.access.service

import com.ovengers.slotkey.access.dto.request.DoorAccessVerifyRequest
import com.ovengers.slotkey.access.dto.response.DoorAccessVerifyResponse
import com.ovengers.slotkey.access.entity.AccessDenyReason
import com.ovengers.slotkey.access.policy.DoorAccessTimePolicy
import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
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
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrNull

@Service
@Transactional(readOnly = true)
class DoorAccessVerificationService(
    private val doorAccessTokenService: DoorAccessTokenService,
    private val doorAccessTokenRepository: DoorAccessTokenRepository,
    private val doorAccessLogService: DoorAccessLogService,
    private val doorAccessTimePolicy: DoorAccessTimePolicy,
    private val memberRepository: MemberRepository,
    private val spaceRepository: SpaceRepository,
    private val clock: Clock,
    private val reservationRepository: ReservationRepository,
    private val reservationStatusHistoryRepository: ReservationStatusHistoryRepository,
    private val entityManager: EntityManager,
) {
    // 제출된 출입 토큰 검증
    @Transactional
    fun verify(
        loginMemberId: Long?,
        request: DoorAccessVerifyRequest,
    ): DoorAccessVerifyResponse {
        val attemptedAt = LocalDateTime.now(clock)

        // 실제 출입을 시도한 로그인 회원 조회
        val actorMember =
            memberRepository.findByIdOrNull(requireNotNull(loginMemberId) { "The given id must not be null" })
                ?: throw BusinessException(ErrorCode.AUTHENTICATION_REQUIRED)

        val requestedSpace =
            spaceRepository.findByIdOrNull(requireNotNull(request.spaceId) { "The given id must not be null" })

        val accessToken =
            doorAccessTokenService.findByRawTokenOrNull(request.token)
                ?: return deny(actorMember, null, requestedSpace, AccessDenyReason.TOKEN_NOT_FOUND, attemptedAt)

        val reservation = accessToken.reservation!!

        // Reservation의 프록시 초기화를 지원하는 기존 공개 getter를 유지한다.
        // TODO: 예약 엔티티의 프로퍼티가 프록시 초기화를 지원하면 프로퍼티 접근으로 변경한다.
        if (loginMemberId != reservation.getMemberId()) {
            return deny(actorMember, reservation, requestedSpace, AccessDenyReason.MEMBER_MISMATCH, attemptedAt)
        }

        // 폐기된 토큰이면 출입 거절
        if (accessToken.isRevoked) {
            return deny(actorMember, reservation, requestedSpace, AccessDenyReason.TOKEN_REVOKED, attemptedAt)
        }

        // 출입 가능한 예약 상태인지 확인
        if (!isActiveReservation(reservation)) {
            return deny(actorMember, reservation, requestedSpace, AccessDenyReason.RESERVATION_NOT_ACTIVE, attemptedAt)
        }

        // 요청한 공간과 예약 공간이 같은지 확인
        if (reservation.getSpaceId() != request.spaceId) {
            return deny(actorMember, reservation, requestedSpace, AccessDenyReason.SPACE_MISMATCH, attemptedAt)
        }

        // 출입 가능한 시간인지 확인
        val timeDenyReason =
            doorAccessTimePolicy.findDenyReason(
                now = attemptedAt,
                startAt = reservation.getStartTime(),
                endAt = reservation.getEndTime(),
                checkedInAt = reservation.checkedInAt,
            )
        if (timeDenyReason != null) {
            return deny(actorMember, reservation, requestedSpace, timeDenyReason, attemptedAt)
        }

        // 모든 ALLOW 경로는 최종 허가 전에 Reservation -> DoorAccessToken 순서로 잠근다.
        // 1차 캐시를 detach하여 REPEATABLE READ 및 영속성 컨텍스트 스냅샷을 우회하고 최신 상태를 읽는다.
        entityManager.detach(reservation)
        entityManager.detach(accessToken)

        // 1. 최신 예약 상태 Locking Read
        var latestReservation =
            reservationRepository.findByIdForUpdate(reservation.getId()).getOrNull()
                ?: run {
                    log.warn("최종 허가 전 예약 잠금 조회 실패(존재하지 않음) - reservationId: {}", reservation.getId())
                    return deny(actorMember, null, requestedSpace, AccessDenyReason.RESERVATION_NOT_ACTIVE, attemptedAt)
                }

        if (!isActiveReservation(latestReservation)) {
            return deny(actorMember, latestReservation, requestedSpace, AccessDenyReason.RESERVATION_NOT_ACTIVE, attemptedAt)
        }

        // 2. 최신 출입 토큰 상태 Locking Read
        val latestToken = doorAccessTokenRepository.findByIdForUpdate(accessToken.id).getOrNull()
        if (latestToken == null || latestToken.isRevoked) {
            return deny(actorMember, latestReservation, requestedSpace, AccessDenyReason.TOKEN_REVOKED, attemptedAt)
        }

        // 3. 최신 예약 정보로 공간 및 시간 재검증
        if (latestReservation.getSpaceId() != request.spaceId) {
            return deny(actorMember, latestReservation, requestedSpace, AccessDenyReason.SPACE_MISMATCH, attemptedAt)
        }

        val latestTimeDenyReason =
            doorAccessTimePolicy.findDenyReason(
                now = attemptedAt,
                startAt = latestReservation.getStartTime(),
                endAt = latestReservation.getEndTime(),
                checkedInAt = latestReservation.checkedInAt,
            )
        if (latestTimeDenyReason != null) {
            return deny(actorMember, latestReservation, requestedSpace, latestTimeDenyReason, attemptedAt)
        }

        // 4. 최초 체크인 여부 판정 및 조건부 전이
        var firstCheckIn = false
        if (latestReservation.getStatus() == ReservationStatus.CONFIRMED) {
            val updatedRows =
                reservationRepository.checkInIfConfirmed(
                    id = latestReservation.getId(),
                    now = attemptedAt,
                    confirmed = ReservationStatus.CONFIRMED,
                    inUse = ReservationStatus.IN_USE,
                )

            if (updatedRows == 1) {
                firstCheckIn = true
                reservationStatusHistoryRepository.save(
                    ReservationStatusHistory.of(
                        reservationId = latestReservation.getId(),
                        changedByMemberId = loginMemberId,
                        fromStatus = ReservationStatus.CONFIRMED,
                        toStatus = ReservationStatus.IN_USE,
                        reason = "FIRST_CHECK_IN",
                        changedAt = attemptedAt,
                    ),
                )
                latestReservation = reservationRepository.findByIdOrNull(latestReservation.getId()) ?: latestReservation
            } else {
                val reloaded = reservationRepository.findByIdForUpdate(latestReservation.getId()).getOrNull()
                if (reloaded == null || !isActiveReservation(reloaded)) {
                    return deny(actorMember, reloaded, requestedSpace, AccessDenyReason.RESERVATION_NOT_ACTIVE, attemptedAt)
                }
                latestReservation = reloaded
            }
        }

        doorAccessLogService.createAllowLog(actorMember, latestReservation, requestedSpace, attemptedAt)

        return DoorAccessVerifyResponse.allow(
            spaceName = requestedSpace!!.name,
            firstCheckIn = firstCheckIn,
            attemptedAt = attemptedAt,
        )
    }

    // 출입 가능한 예약 상태 확인
    private fun isActiveReservation(reservation: Reservation): Boolean =
        reservation.getStatus() == ReservationStatus.CONFIRMED || reservation.getStatus() == ReservationStatus.IN_USE

    // 출입 거절 로그 저장 및 응답 생성
    private fun deny(
        actorMember: Member,
        reservation: Reservation?,
        requestedSpace: Space?,
        reasonCode: AccessDenyReason,
        attemptedAt: LocalDateTime,
    ): DoorAccessVerifyResponse {
        doorAccessLogService.createDenyLog(actorMember, reservation, requestedSpace, reasonCode, attemptedAt)

        return DoorAccessVerifyResponse.deny(
            reasonCode = reasonCode,
            attemptedAt = attemptedAt,
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DoorAccessVerificationService::class.java)
    }
}
