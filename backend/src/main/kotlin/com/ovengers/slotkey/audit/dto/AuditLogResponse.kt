package com.ovengers.slotkey.audit.dto

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import java.time.LocalDateTime

@JvmRecord
data class AuditLogResponse(
    val id: Long,
    val actorMemberId: Long?,
    val action: AuditAction,
    val targetType: AuditTargetType,
    val targetId: Long,
    val reason: String?,
    val beforeValue: String?,
    val afterValue: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(auditLog: AuditLog): AuditLogResponse =
            auditLog.run {
                AuditLogResponse(
                    id = requireNotNull(id) { "id must not be null" },
                    actorMemberId = actorMemberId,
                    action = requireNotNull(action) { "action must not be null" },
                    targetType = requireNotNull(targetType) { "targetType must not be null" },
                    targetId = requireNotNull(targetId) { "targetId must not be null" },
                    reason = reason,
                    beforeValue = beforeValue,
                    afterValue = afterValue,
                    createdAt = requireNotNull(createdAt) { "createdAt must not be null" },
                )
            }
    }
}
