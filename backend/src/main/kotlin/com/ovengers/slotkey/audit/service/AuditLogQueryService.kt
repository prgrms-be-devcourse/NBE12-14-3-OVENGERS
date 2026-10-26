package com.ovengers.slotkey.audit.service

import com.ovengers.slotkey.audit.dto.AdminAuditLogSearchCondition
import com.ovengers.slotkey.audit.dto.AuditLogResponse
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class AuditLogQueryService(
    private val auditLogRepository: AuditLogRepository,
) {
    fun searchAuditLogs(
        condition: AdminAuditLogSearchCondition?,
        pageable: Pageable?,
    ): Page<AuditLogResponse> {
        if (condition != null && condition.dateFrom != null && condition.dateTo != null) {
            if (condition.dateFrom.isAfter(condition.dateTo)) {
                throw BusinessException(ErrorCode.VALIDATION_FAILED)
            }
        }

        var fromInclusive: LocalDateTime? = null
        var toExclusive: LocalDateTime? = null

        if (condition != null) {
            if (condition.dateFrom != null) {
                fromInclusive = condition.dateFrom.atStartOfDay()
            }
            if (condition.dateTo != null) {
                toExclusive = condition.dateTo.plusDays(1).atStartOfDay()
            }
        }

        val forcedSort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        val resolvedPageable = requireNotNull(pageable)
        val queryPageable =
            PageRequest.of(resolvedPageable.pageNumber, resolvedPageable.pageSize, forcedSort)

        return auditLogRepository
            .searchAuditLogs(
                condition?.actorMemberId,
                condition?.action,
                condition?.targetType,
                condition?.targetId,
                fromInclusive,
                toExclusive,
                queryPageable,
            ).map(AuditLogResponse::from)
    }
}
