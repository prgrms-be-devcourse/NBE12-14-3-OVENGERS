package com.ovengers.slotkey.audit.service

import com.ovengers.slotkey.audit.dto.AdminAuditLogSearchCondition
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.isNull
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * #191: 기본 PIT 대상에 AuditLogQueryService를 추가했을 때 드러난 공백을 고정한다.
 * 기존 [AuditLogQueryServiceTest]는 두 날짜를 모두 주거나 모두 생략한 경우만 검증해
 * 한쪽 날짜만 지정한 반개구간 변환 분기가 실행되지 않았다.
 */
@ExtendWith(MockitoExtension::class)
class AuditLogQueryServiceMutationTest {
    @Mock
    private lateinit var auditLogRepository: AuditLogRepository

    @InjectMocks
    private lateinit var auditLogQueryService: AuditLogQueryService

    @Test
    @DisplayName("dateFrom만 지정하면 시작 경계만 변환하고 종료 경계는 null로 전달한다")
    fun searchAuditLogs_onlyDateFrom_convertsFromInclusiveOnly() {
        // given
        val condition =
            AdminAuditLogSearchCondition(
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 9, 20),
                null,
            )
        given(auditLogRepository.searchAuditLogs(any(), any(), any(), any(), any(), any(), any()))
            .willReturn(Page.empty())

        // when
        auditLogQueryService.searchAuditLogs(condition, PageRequest.of(0, 10))

        // then
        val fromCaptor = ArgumentCaptor.forClass(LocalDateTime::class.java)
        verify(auditLogRepository).searchAuditLogs(
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            fromCaptor.capture(),
            isNull(),
            any(Pageable::class.java),
        )
        assertThat(fromCaptor.value).isEqualTo(LocalDateTime.of(2026, 9, 20, 0, 0, 0))
    }

    @Test
    @DisplayName("dateTo만 지정하면 종료 경계만 다음 날 00:00으로 변환하고 시작 경계는 null로 전달한다")
    fun searchAuditLogs_onlyDateTo_convertsToExclusiveOnly() {
        // given
        val condition =
            AdminAuditLogSearchCondition(
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 9, 22),
            )
        given(auditLogRepository.searchAuditLogs(any(), any(), any(), any(), any(), any(), any()))
            .willReturn(Page.empty())

        // when
        auditLogQueryService.searchAuditLogs(condition, PageRequest.of(0, 10))

        // then
        val toCaptor = ArgumentCaptor.forClass(LocalDateTime::class.java)
        verify(auditLogRepository).searchAuditLogs(
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            toCaptor.capture(),
            any(Pageable::class.java),
        )
        assertThat(toCaptor.value).isEqualTo(LocalDateTime.of(2026, 9, 23, 0, 0, 0))
    }
}
