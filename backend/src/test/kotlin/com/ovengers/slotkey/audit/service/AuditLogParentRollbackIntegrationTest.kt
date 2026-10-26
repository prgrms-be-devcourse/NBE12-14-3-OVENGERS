package com.ovengers.slotkey.audit.service

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.support.FixedClockConfig
import com.ovengers.slotkey.support.IntegrationTestSupport
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate

@Import(FixedClockConfig::class)
class AuditLogParentRollbackIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var auditLogService: AuditLogService

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @BeforeEach
    fun setUp() {
        auditLogRepository.deleteAllInBatch()
    }

    @Test
    @DisplayName("부모 트랜잭션에서 감사 로그 기록 후 flush하여 실제 삽입을 관측했더라도 부모 예외로 롤백되면 독립 트랜잭션에서 감사 로그가 존재하지 않는다")
    fun parentTransactionRollback_rollsBackAuditLogEvenAfterFlush() {
        val actorMemberId = 1L
        val action = AuditAction.REGISTER_SPACE
        val targetType = AuditTargetType.SPACE
        val targetId = 100L
        val reason = "부모 롤백 대상 감사 기록"
        val payload = mapOf("name" to "회의실 롤백 테스트", "price" to 10000)

        assertThatThrownBy {
            transactionTemplate.execute { _ ->
                auditLogService.log(
                    actorMemberId,
                    action,
                    targetType,
                    targetId,
                    reason,
                    null,
                    payload,
                )

                // 트랜잭션 내부에서 flush하여 실제 DB 삽입(INSERT)을 유발하고 관측
                entityManager.flush()
                assertThat(auditLogRepository.count()).isEqualTo(1L)

                // 부모 비즈니스 예외 발생으로 트랜잭션 롤백 유도
                throw RuntimeException("부모 비즈니스 처리 중 예외 발생")
            }
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessage("부모 비즈니스 처리 중 예외 발생")

        // 독립 트랜잭션(트랜잭션 템플릿 완료 후 별도 조회)에서 해당 행이 롤백되어 부재함을 검증
        assertThat(auditLogRepository.count()).isZero()
        assertThat(auditLogRepository.findAll()).isEmpty()
    }

    @Test
    @DisplayName("대조군: 부모 트랜잭션이 정상 완료되면 flush된 감사 로그가 독립 트랜잭션에서 정상 커밋되어 조회된다")
    fun parentTransactionCommit_persistsAuditLog() {
        val actorMemberId = 2L
        val action = AuditAction.MODIFY_SPACE
        val targetType = AuditTargetType.SPACE
        val targetId = 200L
        val reason = "정상 커밋 대상 감사 기록"
        val payload = mapOf("name" to "회의실 정상 커밋", "price" to 20000)

        transactionTemplate.execute {
            auditLogService.log(
                actorMemberId,
                action,
                targetType,
                targetId,
                reason,
                null,
                payload,
            )
            entityManager.flush()
            assertThat(auditLogRepository.count()).isEqualTo(1L)
        }

        // 독립 트랜잭션에서 커밋된 행 확인
        val logs: List<AuditLog> = auditLogRepository.findAll()
        assertThat(logs).hasSize(1)
        val saved = logs[0]
        assertThat(saved.actorMemberId).isEqualTo(actorMemberId)
        assertThat(saved.action).isEqualTo(action)
        assertThat(saved.targetType).isEqualTo(targetType)
        assertThat(saved.targetId).isEqualTo(targetId)
        assertThat(saved.reason).isEqualTo(reason)
        assertThat(saved.afterValue).contains("\"name\":\"회의실 정상 커밋\"")
        assertThat(saved.createdAt).isEqualTo(FixedClockConfig.FIXED_DATE_TIME)
    }
}
