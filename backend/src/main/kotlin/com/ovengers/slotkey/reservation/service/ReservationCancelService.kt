package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.access.service.DoorAccessTokenService
import com.ovengers.slotkey.credit.service.CreditService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.outbox.SlotReleasedRecorder
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.policy.ReservationRefundPolicy
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/** 본인 예약 취소. 관리자 강제 취소는 별도 서비스 경로에서 처리한다. */
@Service
class ReservationCancelService(
    private val reservationRepository: ReservationRepository,
    private val memberRepository: MemberRepository,
    private val reservationSlotRepository: ReservationSlotRepository,
    private val reservationStatusHistoryRepository: ReservationStatusHistoryRepository,
    private val creditService: CreditService,
    private val doorAccessTokenService: DoorAccessTokenService,
    private val clock: Clock,
    private val slotReleasedRecorder: SlotReleasedRecorder,
) {
    @Transactional
    fun cancel(
        memberId: Long?,
        reservationId: Long?,
    ): ReservationResponse {
        val requiredMemberId = requireNotNull(memberId)
        val requiredReservationId = requireNotNull(reservationId)
        val reservation =
            reservationRepository.findByIdOrNull(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        if (reservation.memberId != requiredMemberId) {
            throw BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER)
        }

        val now = LocalDateTime.now(clock)
        if (reservation.status == ReservationStatus.HELD) {
            return cancelHeld(reservation, now)
        }

        val updated =
            reservationRepository.cancelIfConfirmedAndBeforeStart(
                requiredReservationId,
                now,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
            )
        if (updated == 0) {
            throw BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "취소할 수 없는 상태입니다.")
        }

        val member =
            memberRepository.findByIdForUpdate(requiredMemberId).orElseThrow {
                BusinessException(ErrorCode.AUTHENTICATION_REQUIRED)
            }
        if (member.status == MemberStatus.WITHDRAWN) {
            throw BusinessException(ErrorCode.ACCOUNT_WITHDRAWN)
        }

        reservationSlotRepository.deleteByReservationId(requiredReservationId)
        doorAccessTokenService.revokeByReservation(requiredReservationId, now, "CANCELLED")

        val totalAmount = reservation.totalAmount
        val penaltyAmount = ReservationRefundPolicy.calculatePenaltyAmount(now, reservation.startTime, totalAmount)
        creditService.refund(requiredMemberId, requiredReservationId, totalAmount)
        if (penaltyAmount > 0) {
            creditService.penalize(requiredMemberId, requiredReservationId, penaltyAmount)
        }

        reservationStatusHistoryRepository.save(
            ReservationStatusHistory.of(
                requiredReservationId,
                requiredMemberId,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED,
                null,
                now,
            ),
        )
        slotReleasedRecorder.record(requiredReservationId, reservation.spaceId, SlotReleasedRecorder.Reason.CANCELLED)

        val cancelled =
            reservationRepository.findByIdOrNull(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        return ReservationResponse.ofCancelled(cancelled, totalAmount - penaltyAmount, penaltyAmount)
    }

    private fun cancelHeld(
        reservation: Reservation,
        now: LocalDateTime,
    ): ReservationResponse {
        val reservationId = reservation.id
        val updated =
            reservationRepository.cancelIfHeld(
                reservationId,
                now,
                ReservationStatus.HELD,
                ReservationStatus.CANCELLED,
            )
        if (updated == 0) {
            throw BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "취소할 수 없는 상태입니다.")
        }

        reservationSlotRepository.deleteByReservationId(reservationId)
        reservationStatusHistoryRepository.save(
            ReservationStatusHistory.of(
                reservationId,
                reservation.memberId,
                ReservationStatus.HELD,
                ReservationStatus.CANCELLED,
                null,
                now,
            ),
        )
        slotReleasedRecorder.record(reservationId, reservation.spaceId, SlotReleasedRecorder.Reason.CANCELLED)

        val cancelled =
            reservationRepository.findByIdOrNull(reservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        return ReservationResponse.ofCancelled(cancelled, 0, 0)
    }
}
