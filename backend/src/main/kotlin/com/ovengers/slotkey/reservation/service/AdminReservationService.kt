package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.access.service.DoorAccessLogService
import com.ovengers.slotkey.access.service.DoorAccessTokenService
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.service.AuditLogService
import com.ovengers.slotkey.credit.service.CreditService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.outbox.SlotReleasedRecorder
import com.ovengers.slotkey.reservation.dto.request.AdminReservationSearchCondition
import com.ovengers.slotkey.reservation.dto.response.AdminReservationDetailResponse
import com.ovengers.slotkey.reservation.dto.response.AdminReservationResponse
import com.ovengers.slotkey.reservation.dto.response.DoorAccessLogResponse
import com.ovengers.slotkey.reservation.dto.response.ReservationStatusHistoryResponse
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/** 관리자 예약 조회와 조건부 강제 취소를 처리한다. */
@Service
class AdminReservationService(
    private val reservationRepository: ReservationRepository,
    private val reservationSlotRepository: ReservationSlotRepository,
    private val statusHistoryRepository: ReservationStatusHistoryRepository,
    private val memberRepository: MemberRepository,
    private val spaceRepository: SpaceRepository,
    private val auditLogService: AuditLogService,
    private val doorAccessTokenService: DoorAccessTokenService,
    private val doorAccessLogService: DoorAccessLogService,
    private val creditService: CreditService,
    private val clock: Clock,
    private val slotReleasedRecorder: SlotReleasedRecorder,
) {
    @Transactional(readOnly = true)
    fun searchReservations(
        condition: AdminReservationSearchCondition?,
        pageable: Pageable?,
    ): Page<AdminReservationResponse> {
        val effectivePageable =
            if (pageable?.sort?.isSorted == true) {
                pageable
            } else {
                PageRequest.of(
                    pageable?.pageNumber ?: 0,
                    pageable?.pageSize ?: 20,
                    Sort.by(Sort.Direction.DESC, "id"),
                )
            }
        val searchCondition = condition ?: AdminReservationSearchCondition(null, null, null)
        val startTimeInclusive = searchCondition.date?.atStartOfDay()
        val startTimeExclusive = searchCondition.date?.plusDays(1)?.atStartOfDay()

        return reservationRepository
            .searchReservations(
                searchCondition.spaceId,
                searchCondition.status,
                startTimeInclusive,
                startTimeExclusive,
                effectivePageable,
            ).map { reservation ->
                AdminReservationResponse.from(
                    reservation,
                    findMemberEmail(reservation.memberId),
                    findSpaceName(reservation.spaceId),
                )
            }
    }

    @Transactional(readOnly = true)
    fun findAllReservations(pageable: Pageable?): Page<AdminReservationResponse> =
        searchReservations(AdminReservationSearchCondition(null, null, null), pageable)

    @Transactional(readOnly = true)
    fun getReservationDetail(reservationId: Long?): AdminReservationDetailResponse {
        val requiredReservationId = requireNotNull(reservationId)
        val reservation =
            reservationRepository.findByIdOrNull(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        val historyResponses: List<ReservationStatusHistoryResponse> =
            statusHistoryRepository
                .findAllByReservationIdOrderByChangedAtAsc(requiredReservationId)
                .map(ReservationStatusHistoryResponse::from)
        val accessLogs: List<DoorAccessLogResponse> =
            doorAccessLogService.findAllByReservationId(requiredReservationId).map(DoorAccessLogResponse::from)

        return AdminReservationDetailResponse.from(
            reservation,
            findMemberEmail(reservation.memberId),
            findSpaceName(reservation.spaceId),
            historyResponses,
            accessLogs,
        )
    }

    @Transactional
    fun forceCancel(
        reservationId: Long?,
        reason: String?,
        adminMemberId: Long?,
    ): AdminReservationResponse {
        val requiredReservationId = requireNotNull(reservationId)
        val requiredAdminMemberId = requireNotNull(adminMemberId)
        val reservation =
            reservationRepository.findByIdOrNull(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        if (reservation.isTerminalState()) {
            throw BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT)
        }

        val previousStatus = reservation.status
        val memberId = reservation.memberId
        val totalAmount = reservation.totalAmount
        val now = LocalDateTime.now(clock)

        if (previousStatus == ReservationStatus.HELD) {
            spaceRepository.findByIdForUpdate(reservation.spaceId).orElseThrow {
                BusinessException(ErrorCode.SPACE_NOT_FOUND)
            }
        }

        val updated =
            reservationRepository.forceCancelIfStatusIs(
                requiredReservationId,
                now,
                previousStatus,
                ReservationStatus.CANCELLED,
            )
        if (updated == 0) {
            throw BusinessException(
                ErrorCode.RESERVATION_STATE_CONFLICT,
                "다른 요청이 먼저 예약 상태를 변경했습니다.",
            )
        }

        statusHistoryRepository.save(
            ReservationStatusHistory.of(
                requiredReservationId,
                requiredAdminMemberId,
                previousStatus,
                ReservationStatus.CANCELLED,
                reason,
                now,
            ),
        )

        if (previousStatus != ReservationStatus.HELD) {
            memberRepository.findByIdForUpdate(memberId)
        }
        reservationSlotRepository.deleteByReservationId(requiredReservationId)
        doorAccessTokenService.revokeByReservation(requiredReservationId, now, "ADMIN_FORCE_CANCEL")
        if (previousStatus != ReservationStatus.HELD) {
            creditService.refund(memberId, requiredReservationId, totalAmount)
        }

        auditLogService.log(
            requiredAdminMemberId,
            AuditAction.FORCE_CANCEL_RESERVATION,
            AuditTargetType.RESERVATION,
            requiredReservationId,
            reason,
            previousStatus,
            ReservationStatus.CANCELLED,
        )
        slotReleasedRecorder.record(
            requiredReservationId,
            reservation.spaceId,
            SlotReleasedRecorder.Reason.FORCE_CANCELLED,
        )

        val cancelled =
            reservationRepository.findByIdOrNull(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        return AdminReservationResponse.from(
            cancelled,
            findMemberEmail(cancelled.memberId),
            findSpaceName(cancelled.spaceId),
        )
    }

    private fun findMemberEmail(memberId: Long): String? = memberRepository.findById(memberId).map { it.email }.orElse(null)

    private fun findSpaceName(spaceId: Long): String? = spaceRepository.findById(spaceId).map { it.name }.orElse(null)
}
