package com.ovengers.slotkey.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.audit.entity.AuditAction;
import com.ovengers.slotkey.audit.entity.AuditLog;
import com.ovengers.slotkey.audit.entity.AuditTargetType;
import com.ovengers.slotkey.audit.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void log(
            Long actorMemberId,
            AuditAction action,
            AuditTargetType targetType,
            Long targetId,
            String reason,
            Object beforeValue,
            Object afterValue) {
        String beforeJson = serialize(beforeValue);
        String afterJson = serialize(afterValue);

        AuditLog auditLog = AuditLog.builder()
                .actorMemberId(actorMemberId)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .reason(reason)
                .beforeValue(beforeJson)
                .afterValue(afterJson)
                .build();

        auditLogRepository.save(auditLog);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void log(
            Long actorMemberId,
            AuditAction action,
            AuditTargetType targetType,
            Long targetId,
            Object beforeValue,
            Object afterValue) {
        log(actorMemberId, action, targetType, targetId, null, beforeValue, afterValue);
    }

    private String serialize(Object object) {
        if (object == null) {
            return null;
        }
        if (object instanceof String str) {
            return str;
        }
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.warn("감사 로그 데이터 직렬화 실패. toString() 대체: {}", e.getMessage());
            return object.toString();
        }
    }

    public AuditLogService(
            final AuditLogRepository auditLogRepository,
            final ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }
}
