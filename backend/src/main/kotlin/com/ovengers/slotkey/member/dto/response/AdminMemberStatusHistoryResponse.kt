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
                requireNotNull(auditLog.id),
                auditLog.action,
                auditLog.actorMemberId,
                auditLog.reason,
                auditLog.beforeValue,
                auditLog.afterValue,
                requireNotNull(auditLog.createdAt),
            )
    }
}
