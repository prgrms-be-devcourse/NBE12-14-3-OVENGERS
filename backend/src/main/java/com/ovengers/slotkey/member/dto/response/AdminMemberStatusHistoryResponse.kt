package com.ovengers.slotkey.member.dto.response

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import java.time.LocalDateTime

@JvmRecord
data class AdminMemberStatusHistoryResponse(
    val historyId: Long,
    val action: AuditAction,
    val actorMemberId: Long?,
    val reason: String?,
    val beforeValue: String?,
    val afterValue: String?,
    val changedAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(auditLog: AuditLog) =
            AdminMemberStatusHistoryResponse(
                auditLog.getId(),
                auditLog.getAction(),
                auditLog.getActorMemberId(),
                auditLog.getReason(),
                auditLog.getBeforeValue(),
                auditLog.getAfterValue(),
                auditLog.getCreatedAt(),
            )
    }
}
