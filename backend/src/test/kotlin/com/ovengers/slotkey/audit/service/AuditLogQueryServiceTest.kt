package com.ovengers.slotkey.audit.service

import com.ovengers.slotkey.audit.dto.AdminAuditLogSearchCondition
import com.ovengers.slotkey.audit.dto.AuditLogResponse
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.time.LocalDate
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class AuditLogQueryServiceTest {
    @Mock
    private lateinit var auditLogRepository: AuditLogRepository

    @InjectMocks
    private lateinit var auditLogQueryService: AuditLogQueryService

    @Test
    @DisplayName("dateFrom과 dateTo가 전달되면 KST 기준 [dateFrom 00:00:00, dateTo+1일 00:00:00) 반개구간으로 변환된다")
    fun searchAuditLogs_halfOpenInterval_conversion() {
        // given
        val condition =
            AdminAuditLogSearchCondition(
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 22),
            )
        val requestPageable = PageRequest.of(0, 10)
        given(auditLogRepository.searchAuditLogs(any(), any(), any(), any(), any(), any(), any()))
            .willReturn(Page.empty())

        // when
        auditLogQueryService.searchAuditLogs(condition, requestPageable)

        // then
        val fromCaptor = ArgumentCaptor.forClass(LocalDateTime::class.java)
        val toCaptor = ArgumentCaptor.forClass(LocalDateTime::class.java)

        verify(auditLogRepository).searchAuditLogs(
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            fromCaptor.capture(),
            toCaptor.capture(),
            any(Pageable::class.java),
        )

        assertThat(fromCaptor.value).isEqualTo(LocalDateTime.of(2026, 9, 20, 0, 0, 0))
        assertThat(toCaptor.value).isEqualTo(LocalDateTime.of(2026, 9, 23, 0, 0, 0))
    }

    @Test
    @DisplayName("dateFrom이 dateTo보다 늦으면 VALIDATION_FAILED 예외를 던지고 repository를 호출하지 않는다")
    fun searchAuditLogs_dateFromAfterDateTo_throwsValidationFailed() {
        // given
        val condition =
            AdminAuditLogSearchCondition(
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 20),
            )
        val requestPageable = PageRequest.of(0, 10)

        // when & then
        assertThatThrownBy { auditLogQueryService.searchAuditLogs(condition, requestPageable) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED)

        verifyNoInteractions(auditLogRepository)
    }

    @Test
    @DisplayName("요청의 sort 파라미터를 무시하고 서버 고정 createdAt DESC, id DESC 정렬로 재구성하여 repository에 전달한다")
    fun searchAuditLogs_ignoresClientSort_andForcesServerSort() {
        // given
        val condition =
            AdminAuditLogSearchCondition(
                1L,
                AuditAction.MODIFY_SPACE,
                AuditTargetType.SPACE,
                10L,
                null,
                null,
            )
        // 클라이언트가 임의의 정렬(예: targetId ASC)을 요청한 경우
        val clientPageable = PageRequest.of(1, 15, Sort.by(Sort.Direction.ASC, "targetId"))
        given(auditLogRepository.searchAuditLogs(any(), any(), any(), any(), any(), any(), any()))
            .willReturn(Page.empty())

        // when
        auditLogQueryService.searchAuditLogs(condition, clientPageable)

        // then
        val pageableCaptor = ArgumentCaptor.forClass(Pageable::class.java)
        verify(auditLogRepository).searchAuditLogs(
            eq(1L),
            eq(AuditAction.MODIFY_SPACE),
            eq(AuditTargetType.SPACE),
            eq(10L),
            isNull(),
            isNull(),
            pageableCaptor.capture(),
        )

        val passedPageable = pageableCaptor.value
        assertThat(passedPageable.pageNumber).isEqualTo(1)
        assertThat(passedPageable.pageSize).isEqualTo(15)
        assertThat(passedPageable.sort).isEqualTo(
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
        )
    }

    @Test
    @DisplayName("AuditLog 엔티티의 모든 필드(nullable 포함, JSON 원본 문자열 보존)가 AuditLogResponse로 매핑된다")
    fun searchAuditLogs_mapsAllFieldsCorrectly() {
        // given
        val createdAt = LocalDateTime.of(2026, 9, 20, 14, 30, 0)
        val auditLog =
            AuditLog
                .builder()
                .id(100L)
                .actorMemberId(null) // nullable 시스템 작업
                .action(AuditAction.FORCE_CANCEL_RESERVATION)
                .targetType(AuditTargetType.RESERVATION)
                .targetId(200L)
                .reason(null) // nullable 사유
                .beforeValue("{\"status\":\"CONFIRMED\"}")
                .afterValue("{\"status\":\"CANCELLED\"}")
                .createdAt(createdAt)
                .build()

        val entityPage: Page<AuditLog> = PageImpl(listOf(auditLog), PageRequest.of(0, 10), 1L)
        given(auditLogRepository.searchAuditLogs(any(), any(), any(), any(), any(), any(), any()))
            .willReturn(entityPage)

        // when
        val responsePage: Page<AuditLogResponse> =
            auditLogQueryService.searchAuditLogs(null, PageRequest.of(0, 10))

        // then
        assertThat(responsePage.content).hasSize(1)
        val response = responsePage.content[0]

        assertThat(response.id).isEqualTo(100L)
        assertThat(response.actorMemberId).isNull()
        assertThat(response.action).isEqualTo(AuditAction.FORCE_CANCEL_RESERVATION)
        assertThat(response.targetType).isEqualTo(AuditTargetType.RESERVATION)
        assertThat(response.targetId).isEqualTo(200L)
        assertThat(response.reason).isNull()
        assertThat(response.beforeValue).isEqualTo("{\"status\":\"CONFIRMED\"}")
        assertThat(response.afterValue).isEqualTo("{\"status\":\"CANCELLED\"}")
        assertThat(response.createdAt).isEqualTo(createdAt)
    }
}
