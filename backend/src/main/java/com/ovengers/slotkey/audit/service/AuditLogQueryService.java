package com.ovengers.slotkey.audit.service;

import com.ovengers.slotkey.audit.dto.AdminAuditLogSearchCondition;
import com.ovengers.slotkey.audit.dto.AuditLogResponse;
import com.ovengers.slotkey.audit.repository.AuditLogRepository;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional(readOnly = true)
public class AuditLogQueryService {

    private final AuditLogRepository auditLogRepository;

    public Page<AuditLogResponse> searchAuditLogs(AdminAuditLogSearchCondition condition, Pageable pageable) {
        if (condition != null && condition.dateFrom() != null && condition.dateTo() != null) {
            if (condition.dateFrom().isAfter(condition.dateTo())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }

        LocalDateTime fromInclusive = null;
        LocalDateTime toExclusive = null;

        if (condition != null) {
            if (condition.dateFrom() != null) {
                fromInclusive = condition.dateFrom().atStartOfDay();
            }
            if (condition.dateTo() != null) {
                toExclusive = condition.dateTo().plusDays(1).atStartOfDay();
            }
        }

        Sort forcedSort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Pageable queryPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), forcedSort);

        return auditLogRepository.searchAuditLogs(
                condition != null ? condition.actorMemberId() : null,
                condition != null ? condition.action() : null,
                condition != null ? condition.targetType() : null,
                condition != null ? condition.targetId() : null,
                fromInclusive,
                toExclusive,
                queryPageable
        ).map(AuditLogResponse::from);
    }

    public AuditLogQueryService(final AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }
}
