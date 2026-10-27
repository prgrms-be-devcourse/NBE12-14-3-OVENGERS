package com.ovengers.slotkey.audit.service

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.support.FixedClockConfig
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate

@Import(FixedClockConfig::class)
class AuditLogServiceTransactionTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var auditLogService: AuditLogService

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @BeforeEach
    fun setUp() {
        auditLogRepository.deleteAllInBatch()
    }

    @Test
    @DisplayName("트랜잭션 없이 호출하면 IllegalTransactionStateException이 발생하고 감사 로그가 저장되지 않는다")
    fun log_withoutTransaction_throwsIllegalTransactionStateException_andDoesNotSave() {
        // when & then
        assertThatThrownBy {
            auditLogService.log(
                1L,
                AuditAction.REGISTER_SPACE,
                AuditTargetType.SPACE,
                10L,
                "트랜잭션 없는 호출",
                null,
                mapOf("name" to "회의실 A"),
            )
        }.isInstanceOf(IllegalTransactionStateException::class.java)

        assertThat(auditLogRepository.count()).isZero()
    }

    @Test
    @DisplayName("트랜잭션 내부에서 호출하면 감사 로그가 정상 저장되고 고정된 시각(createdAt)으로 감사 기록된다")
    fun log_withinTransaction_savesAuditLogWithFixedCreatedAt() {
        // given
        val actorMemberId = 1L
        val action = AuditAction.REGISTER_SPACE
        val targetType = AuditTargetType.SPACE
        val targetId = 10L
        val reason = "신규 공간 등록"
        val after: Map<String, Any> = mapOf("name" to "회의실 A", "price" to 3000)

        // when
        transactionTemplate.executeWithoutResult {
            auditLogService.log(
                actorMemberId,
                action,
                targetType,
                targetId,
                reason,
                null,
                after,
            )
        }

        // then
        val auditLogs: List<AuditLog> = auditLogRepository.findAll()
        assertThat(auditLogs).hasSize(1)

        val saved = auditLogs[0]
        assertThat(saved.actorMemberId).isEqualTo(actorMemberId)
        assertThat(saved.action).isEqualTo(action)
        assertThat(saved.targetType).isEqualTo(targetType)
        assertThat(saved.targetId).isEqualTo(targetId)
        assertThat(saved.reason).isEqualTo(reason)
        assertThat(saved.beforeValue).isNull()
        assertThat(saved.afterValue).contains("\"name\":\"회의실 A\"")
        assertThat(saved.createdAt).isEqualTo(FixedClockConfig.FIXED_DATE_TIME)
    }
}
