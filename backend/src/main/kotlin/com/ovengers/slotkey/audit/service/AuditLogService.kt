package com.ovengers.slotkey.audit.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class AuditLogService(
    private val auditLogRepository: AuditLogRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun log(
        actorMemberId: Long?,
        action: AuditAction?,
        targetType: AuditTargetType?,
        targetId: Long?,
        reason: String?,
        beforeValue: Any?,
        afterValue: Any?,
    ) {
        val beforeJson = serialize(beforeValue)
        val afterJson = serialize(afterValue)

        val auditLog =
            AuditLog
                .builder()
                .actorMemberId(actorMemberId)
                .action(requireNotNull(action))
                .targetType(requireNotNull(targetType))
                .targetId(targetId)
                .reason(reason)
                .beforeValue(beforeJson)
                .afterValue(afterJson)
                .build()

        auditLogRepository.save(auditLog)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun log(
        actorMemberId: Long?,
        action: AuditAction?,
        targetType: AuditTargetType?,
        targetId: Long?,
        beforeValue: Any?,
        afterValue: Any?,
    ) {
        log(actorMemberId, action, targetType, targetId, null, beforeValue, afterValue)
    }

    private fun serialize(value: Any?): String? =
        when (value) {
            null -> null
            is String -> value
            else ->
                try {
                    objectMapper.writeValueAsString(value)
                } catch (e: JsonProcessingException) {
                    logger.warn("감사 로그 데이터 직렬화 실패. toString() 대체: {}", e.message)
                    value.toString()
                }
        }

    companion object {
        private val logger = LoggerFactory.getLogger(AuditLogService::class.java)
    }
}
