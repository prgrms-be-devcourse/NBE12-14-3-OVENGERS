package com.ovengers.slotkey.reservation.dto.response

import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import java.time.LocalDateTime

/**
 * 예약 상태 전이 이력 한 건. 사용자 응답에는 변경 주체(changedByMemberId)를 넣지 않는다
 * — 관리자 강제 취소 시 관리자 id가 노출되지 않게 하기 위함. 관리자 응답에서 필요하면 별도로 확장한다.
 */
@JvmRecord
data class ReservationStatusHistoryResponse(
    val fromStatus: String?,
    val toStatus: String,
    val reason: String?,
    val changedAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(history: ReservationStatusHistory): ReservationStatusHistoryResponse =
            ReservationStatusHistoryResponse(
                fromStatus = history.fromStatus?.name,
                toStatus = history.toStatus.name,
                reason = history.reason,
                changedAt = history.changedAt,
            )
    }
}
