package com.ovengers.slotkey.audit.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.captureNonNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class AuditLogServiceTest {
    @Mock
    private lateinit var auditLogRepository: AuditLogRepository

    private lateinit var objectMapper: ObjectMapper
    private lateinit var auditLogService: AuditLogService

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper()
        objectMapper.registerModule(JavaTimeModule())
        auditLogService = AuditLogService(auditLogRepository, objectMapper)
    }

    @Test
    @DisplayName("사유와 전/후 객체가 주어지면 JSON으로 직렬화하여 감사 로그를 저장한다")
    fun log_withReasonAndObjects_savesAuditLog() {
        // given
        val actorMemberId = 1L
        val action = AuditAction.MODIFY_SPACE
        val targetType = AuditTargetType.SPACE
        val targetId = 10L
        val reason = "가격 인상"
        val before: Map<String, Any> = mapOf("price" to 3000)
        val after: Map<String, Any> = mapOf("price" to 5000)

        // when
        auditLogService.log(actorMemberId, action, targetType, targetId, reason, before, after)

        // then
        val captor = ArgumentCaptor.forClass(AuditLog::class.java)
        verify(auditLogRepository).save(captureNonNull(captor))

        val saved = captor.value
        assertThat(saved.actorMemberId).isEqualTo(actorMemberId)
        assertThat(saved.action).isEqualTo(action)
        assertThat(saved.targetType).isEqualTo(targetType)
        assertThat(saved.targetId).isEqualTo(targetId)
        assertThat(saved.reason).isEqualTo(reason)
        assertThat(saved.beforeValue).contains("\"price\":3000")
        assertThat(saved.afterValue).contains("\"price\":5000")
    }

    @Test
    @DisplayName("사유 없이 호출하면 reason이 null로 저장된다")
    fun log_withoutReason_savesAuditLogWithNullReason() {
        // given
        val actorMemberId = 1L
        val action = AuditAction.REGISTER_SPACE
        val targetType = AuditTargetType.SPACE
        val targetId = 20L
        val after: Map<String, Any> = mapOf("name" to "회의실 A")

        // when
        auditLogService.log(actorMemberId, action, targetType, targetId, null, after)

        // then
        val captor = ArgumentCaptor.forClass(AuditLog::class.java)
        verify(auditLogRepository).save(captureNonNull(captor))

        val saved = captor.value
        assertThat(saved.reason).isNull()
        assertThat(saved.beforeValue).isNull()
        assertThat(saved.afterValue).contains("\"name\":\"회의실 A\"")
    }

    @Test
    @DisplayName("beforeValue와 afterValue가 각각 null이면 엔티티 필드도 null로 저장된다")
    fun log_withNullBeforeAndAfter_savesAuditLogWithNullValues() {
        // given
        val actorMemberId = 2L
        val action = AuditAction.REGISTER_SPACE
        val targetType = AuditTargetType.SPACE
        val targetId = 30L
        val reason = "신규 공간 등록"

        // when
        auditLogService.log(actorMemberId, action, targetType, targetId, reason, null, null)

        // then
        val captor = ArgumentCaptor.forClass(AuditLog::class.java)
        verify(auditLogRepository).save(captureNonNull(captor))

        val saved = captor.value
        assertThat(saved.actorMemberId).isEqualTo(actorMemberId)
        assertThat(saved.action).isEqualTo(action)
        assertThat(saved.targetType).isEqualTo(targetType)
        assertThat(saved.targetId).isEqualTo(targetId)
        assertThat(saved.reason).isEqualTo(reason)
        assertThat(saved.beforeValue).isNull()
        assertThat(saved.afterValue).isNull()
    }

    @Test
    @DisplayName("ObjectMapper가 JsonProcessingException을 던지면 toString() 결과로 대체하여 저장을 완료한다")
    fun log_whenSerializationThrowsException_fallbacksToStringAndSaves() {
        // given
        val mockObjectMapper = mock(ObjectMapper::class.java)
        val serviceWithMock = AuditLogService(auditLogRepository, mockObjectMapper)

        val actorMemberId = 1L
        val action = AuditAction.MODIFY_SPACE
        val targetType = AuditTargetType.SPACE
        val targetId = 10L
        val reason = "직렬화 오류 테스트"

        class TestPayload(
            private val value: String,
        ) {
            override fun toString(): String = "TestPayload($value)"
        }

        val beforeObj = TestPayload("before-state")
        val afterObj = TestPayload("after-state")

        given(mockObjectMapper.writeValueAsString(anyNonNull()))
            .willThrow(object : JsonProcessingException("JSON parse error") {})

        // when
        serviceWithMock.log(actorMemberId, action, targetType, targetId, reason, beforeObj, afterObj)

        // then
        val captor = ArgumentCaptor.forClass(AuditLog::class.java)
        verify(auditLogRepository).save(captureNonNull(captor))

        val saved = captor.value
        assertThat(saved.actorMemberId).isEqualTo(actorMemberId)
        assertThat(saved.action).isEqualTo(action)
        assertThat(saved.targetType).isEqualTo(targetType)
        assertThat(saved.targetId).isEqualTo(targetId)
        assertThat(saved.reason).isEqualTo(reason)
        assertThat(saved.beforeValue).isEqualTo(beforeObj.toString())
        assertThat(saved.afterValue).isEqualTo(afterObj.toString())
    }

    @Test
    @DisplayName("문자열 타입의 값은 추가 직렬화 없이 그대로 저장된다")
    fun log_withStringValues_savesRawString() {
        // given
        val beforeStr = "{\"status\":\"ACTIVE\"}"
        val afterStr = "{\"status\":\"INACTIVE\"}"

        // when
        auditLogService.log(1L, AuditAction.SUSPEND_MEMBER, AuditTargetType.MEMBER, 5L, "규정 위반", beforeStr, afterStr)

        // then
        val captor = ArgumentCaptor.forClass(AuditLog::class.java)
        verify(auditLogRepository).save(captureNonNull(captor))

        val saved = captor.value
        assertThat(saved.beforeValue).isEqualTo(beforeStr)
        assertThat(saved.afterValue).isEqualTo(afterStr)
    }
}
