package com.ovengers.slotkey.audit.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.junit.jupiter.MockitoExtension

/**
 * #191: 기본 PIT 대상에 AuditLogService를 추가했을 때 드러난 공개 nullable 계약 공백을 고정한다.
 * `action`/`targetType`은 `log` 시그니처에서 nullable이지만 null이면 저장 전에
 * IllegalArgumentException으로 거절되어야 한다. 기존 [AuditLogServiceTest]는 non-null만 검증한다.
 */
@ExtendWith(MockitoExtension::class)
class AuditLogServiceMutationTest {
    @Mock
    private lateinit var auditLogRepository: AuditLogRepository

    private lateinit var auditLogService: AuditLogService

    @BeforeEach
    fun setUp() {
        val objectMapper = ObjectMapper().registerModule(JavaTimeModule())
        auditLogService = AuditLogService(auditLogRepository, objectMapper)
    }

    @Test
    @DisplayName("action이 null이면 IllegalArgumentException을 던지고 감사 로그를 저장하지 않는다")
    fun log_nullAction_throwsIllegalArgumentAndNeverSaves() {
        assertThatThrownBy {
            auditLogService.log(
                1L,
                null,
                AuditTargetType.SPACE,
                10L,
                "사유",
                mapOf("price" to 3000),
                mapOf("price" to 5000),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        then(auditLogRepository).should(never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    @DisplayName("targetType이 null이면 IllegalArgumentException을 던지고 감사 로그를 저장하지 않는다")
    fun log_nullTargetType_throwsIllegalArgumentAndNeverSaves() {
        assertThatThrownBy {
            auditLogService.log(
                1L,
                AuditAction.MODIFY_SPACE,
                null,
                10L,
                "사유",
                mapOf("price" to 3000),
                mapOf("price" to 5000),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        then(auditLogRepository).should(never()).save(org.mockito.ArgumentMatchers.any())
    }
}
