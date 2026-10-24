package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.access.service.DoorAccessTokenService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/** 명시적 체크아웃. 슬롯 반환과 환불은 수행하지 않는다. */
@Service
class ReservationCheckOutService(
    private val reservationRepository: ReservationRepository,
    private val reservationStatusHistoryRepository: ReservationStatusHistoryRepository,
    private val clock: Clock,
    private val doorAccessTokenService: DoorAccessTokenService,
) {
    @Transactional
    fun checkOut(
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
        val updated =
            reservationRepository.checkOutIfInUse(
                requiredReservationId,
                now,
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED,
            )
        if (updated == 0) {
            throw BusinessException(ErrorCode.RESERVATION_STATE_CONFLICT, "체크아웃할 수 없는 상태입니다.")
        }

        doorAccessTokenService.revokeByReservation(requiredReservationId, now, "CHECKED_OUT")
        reservationStatusHistoryRepository.save(
            ReservationStatusHistory.of(
                requiredReservationId,
                requiredMemberId,
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED,
                null,
                now,
            ),
        )

        val checkedOut =
            reservationRepository.findByIdOrNull(requiredReservationId)
                ?: throw BusinessException(ErrorCode.RESERVATION_NOT_FOUND)
        return ReservationResponse.from(checkedOut)
    }
}
