package com.ovengers.slotkey.reservation.dto.response

import com.ovengers.slotkey.access.entity.AccessDenyReason
import com.ovengers.slotkey.access.entity.AccessResult
import com.ovengers.slotkey.access.entity.DoorAccessLog
import java.time.LocalDateTime

/**
 * 출입 시도 이력 응답 (관리자 예약 상세 조회 포함용).
 */
@JvmRecord
data class DoorAccessLogResponse(
    val accessLogId: Long?,
    val attemptedAt: LocalDateTime?,
    val result: AccessResult?,
    val reasonCode: AccessDenyReason?,
) {
    companion object {
        fun from(log: DoorAccessLog): DoorAccessLogResponse =
            DoorAccessLogResponse(
                accessLogId = log.id,
                attemptedAt = log.attemptedAt,
                result = log.result,
                reasonCode = log.reasonCode,
            )
    }
}
