package com.ovengers.slotkey.access.dto.response

import com.ovengers.slotkey.access.entity.AccessDenyReason
import com.ovengers.slotkey.access.entity.AccessResult
import com.ovengers.slotkey.access.entity.DoorAccessLog
import java.time.LocalDateTime

// TODO: Java 호출부·테스트 전환 완료 후 record 접근자 사용과 JSON 호환을 확인하고 @JvmRecord 제거를 검토한다.
@JvmRecord
data class DoorAccessLogResponse(
    val accessLogId: Long?,
    val attemptedAt: LocalDateTime?,
    val requestedSpaceName: String?,
    val result: AccessResult?,
    val reasonCode: AccessDenyReason?,
) {
    // TODO: Java 정적 호출부·테스트가 모두 전환되면 팩토리의 @JvmStatic 제거를 검토한다.
    companion object {
        @JvmStatic
        fun from(log: DoorAccessLog): DoorAccessLogResponse =
            DoorAccessLogResponse(
                accessLogId = log.id,
                attemptedAt = log.attemptedAt,
                requestedSpaceName = log.requestedSpace?.name,
                result = log.result,
                reasonCode = log.reasonCode,
            )
    }
}
