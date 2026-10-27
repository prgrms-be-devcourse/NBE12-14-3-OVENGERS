package com.ovengers.slotkey.access.service

import com.ovengers.slotkey.access.authorization.DoorAccessAuthorizationService
import com.ovengers.slotkey.access.dto.response.DoorAccessLogResponse
import com.ovengers.slotkey.access.entity.AccessDenyReason
import com.ovengers.slotkey.access.entity.AccessResult
import com.ovengers.slotkey.access.entity.DoorAccessLog
import com.ovengers.slotkey.access.repository.DoorAccessLogRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.space.entity.Space
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.Collections

@Service
@Transactional(readOnly = true)
class DoorAccessLogService(
    private val doorAccessLogRepository: DoorAccessLogRepository,
    private val reservationRepository: ReservationRepository,
    private val doorAccessAuthorizationService: DoorAccessAuthorizationService,
) {
    // 출입 시도 결과에 대한 로그 생성
    @Transactional
    fun create(
        actorMember: Member?,
        reservation: Reservation?,
        requestedSpace: Space?,
        result: AccessResult?,
        reasonCode: AccessDenyReason?,
        attemptedAt: LocalDateTime?,
    ): DoorAccessLog =
        doorAccessLogRepository.save(
            DoorAccessLog(
                actorMember = actorMember,
                reservation = reservation,
                requestedSpace = requestedSpace,
                result = result,
                reasonCode = reasonCode,
                attemptedAt = attemptedAt,
            ),
        )

    // 출입 허용 로그 생성
    @Transactional
    fun createAllowLog(
        actorMember: Member?,
        reservation: Reservation?,
        requestedSpace: Space?,
        attemptedAt: LocalDateTime?,
    ): DoorAccessLog =
        create(
            actorMember = actorMember,
            reservation = reservation,
            requestedSpace = requestedSpace,
            result = AccessResult.ALLOW,
            reasonCode = null,
            attemptedAt = attemptedAt,
        )

    // 출입 거절 로그 생성
    @Transactional
    fun createDenyLog(
        actorMember: Member?,
        reservation: Reservation?,
        requestedSpace: Space?,
        reasonCode: AccessDenyReason?,
        attemptedAt: LocalDateTime?,
    ): DoorAccessLog {
        val denyReason = reasonCode ?: throw BusinessException(ErrorCode.ACCESS_DENY_REASON_REQUIRED)

        return create(
            actorMember = actorMember,
            reservation = reservation,
            requestedSpace = requestedSpace,
            result = AccessResult.DENY,
            reasonCode = denyReason,
            attemptedAt = attemptedAt,
        )
    }

    // 출입 로그 단건 조회
    fun findById(accessLogId: Long?): DoorAccessLog =
        doorAccessLogRepository.findByIdOrNull(requireNotNull(accessLogId) { "The given id must not be null" })
            ?: throw BusinessException(ErrorCode.ACCESS_LOG_NOT_FOUND)

    // 출입 로그 전체 조회
    fun findAll(): List<DoorAccessLog> = doorAccessLogRepository.findAll()

    // 특정 예약의 출입 로그를 최신순으로 조회
    fun findAllByReservationId(reservationId: Long?): List<DoorAccessLog> =
        doorAccessLogRepository.findAllByReservationIdOrderByAttemptedAtDesc(reservationId)

    // 특정 예약의 출입 로그 응답 목록 조회
    fun findResponsesByReservationId(
        loginMemberId: Long?,
        reservationId: Long?,
    ): List<DoorAccessLogResponse> {
        val reservation =
            reservationRepository.findByIdOrNull(requireNotNull(reservationId) { "The given id must not be null" })
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)

        // Reservation의 프록시 초기화를 지원하는 기존 공개 getter를 유지한다.
        // TODO: 예약 엔티티의 프로퍼티가 프록시 초기화를 지원하면 프로퍼티 접근으로 변경한다.
        doorAccessAuthorizationService.validateOwner(loginMemberId, reservation.getMemberId())

        // 기존 Java Stream.toList()가 반환하던 수정 불가 목록 계약을 유지한다.
        return Collections.unmodifiableList(findAllByReservationId(reservationId).map(DoorAccessLogResponse::from))
    }

    // 해당 예약의 출입 성공 기록 존재 여부
    fun hasSuccessfulAccess(reservationId: Long?): Boolean =
        doorAccessLogRepository.existsByReservationIdAndResult(reservationId, AccessResult.ALLOW)
}
