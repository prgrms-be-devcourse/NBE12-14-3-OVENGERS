package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.reservation.dto.response.ReservationStatusHistoryResponse
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class ReservationStatusHistoryService(
    private val reservationStatusHistoryRepository: ReservationStatusHistoryRepository,
) {
    // 예약 상태 변경 이력 저장
    @Transactional
    fun create(
        reservationId: Long?,
        changedByMemberId: Long?,
        fromStatus: ReservationStatus?,
        toStatus: ReservationStatus,
        reason: String?,
        changedAt: LocalDateTime,
    ): ReservationStatusHistoryResponse {
        val history =
            ReservationStatusHistory.of(
                reservationId,
                changedByMemberId,
                fromStatus,
                toStatus,
                reason,
                changedAt,
            )

        val savedHistory = reservationStatusHistoryRepository.save(history)

        return ReservationStatusHistoryResponse.from(savedHistory)
    }

    // 예약 ID 기준 상태 변경 이력 조회
    fun findAllByReservationId(reservationId: Long?): List<ReservationStatusHistoryResponse> {
        val requiredReservationId = requireNotNull(reservationId)

        return reservationStatusHistoryRepository
            .findAllByReservationIdOrderByChangedAtAsc(requiredReservationId)
            .map(ReservationStatusHistoryResponse::from)
    }
}
