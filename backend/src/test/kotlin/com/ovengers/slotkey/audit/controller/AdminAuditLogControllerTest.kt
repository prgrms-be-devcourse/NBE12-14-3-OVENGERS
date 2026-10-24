package com.ovengers.slotkey.audit.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.ovengers.slotkey.audit.dto.AdminAuditLogSearchCondition
import com.ovengers.slotkey.audit.dto.AuditLogResponse
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.service.AuditLogQueryService
import com.ovengers.slotkey.global.error.GlobalExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
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
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDate
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class AdminAuditLogControllerTest {
    private lateinit var mockMvc: MockMvc

    @Mock
    private lateinit var auditLogQueryService: AuditLogQueryService

    @InjectMocks
    private lateinit var adminAuditLogController: AdminAuditLogController

    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper()
        objectMapper.registerModule(JavaTimeModule())
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        mockMvc =
            MockMvcBuilders
                .standaloneSetup(adminAuditLogController)
                .setCustomArgumentResolvers(PageableHandlerMethodArgumentResolver())
                .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    @Test
    @DisplayName("GET /api/v1/admin/audit-logs 기본 요청 시 200 OK와 PageResponse 규격의 감사 로그 목록을 반환한다")
    fun getAuditLogs_default_returnsPageResponse() {
        // given
        val logResponse =
            AuditLogResponse(
                1L,
                10L,
                AuditAction.REGISTER_SPACE,
                AuditTargetType.SPACE,
                100L,
                "신규 회의실 등록",
                null,
                "{\"name\":\"회의실 A\"}",
                LocalDateTime.of(2026, 9, 20, 10, 0, 0),
            )
        val page: Page<AuditLogResponse> = PageImpl(listOf(logResponse), PageRequest.of(0, 20), 1L)
        given(
            auditLogQueryService.searchAuditLogs(
                any(AdminAuditLogSearchCondition::class.java),
                any(Pageable::class.java),
            ),
        ).willReturn(page)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/audit-logs")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(1L))
            .andExpect(jsonPath("$.data.content[0].actorMemberId").value(10L))
            .andExpect(jsonPath("$.data.content[0].action").value("REGISTER_SPACE"))
            .andExpect(jsonPath("$.data.content[0].targetType").value("SPACE"))
            .andExpect(jsonPath("$.data.content[0].targetId").value(100L))
            .andExpect(jsonPath("$.data.content[0].reason").value("신규 회의실 등록"))
            .andExpect(jsonPath("$.data.content[0].beforeValue").value(nullValue()))
            .andExpect(jsonPath("$.data.content[0].afterValue").value("{\"name\":\"회의실 A\"}"))
            .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-09-20T10:00:00"))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.totalPages").value(1))

        val pageableCaptor = ArgumentCaptor.forClass(Pageable::class.java)
        verify(auditLogQueryService).searchAuditLogs(any(), pageableCaptor.capture())
        assertThat(pageableCaptor.value.pageSize).isEqualTo(20)
    }

    @Test
    @DisplayName("6개 필터와 페이징 파라미터가 SearchCondition과 Pageable로 정확하게 매핑되어 서비스로 전달된다")
    fun getAuditLogs_withAllFilters_bindsCorrectly() {
        // given
        val emptyPage: Page<AuditLogResponse> = PageImpl(emptyList(), PageRequest.of(2, 10), 0L)
        given(
            auditLogQueryService.searchAuditLogs(
                any(AdminAuditLogSearchCondition::class.java),
                any(Pageable::class.java),
            ),
        ).willReturn(emptyPage)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/audit-logs")
                    .param("actorMemberId", "5")
                    .param("action", "MODIFY_SPACE")
                    .param("targetType", "SPACE")
                    .param("targetId", "50")
                    .param("dateFrom", "2026-09-10")
                    .param("dateTo", "2026-09-20")
                    .param("page", "2")
                    .param("size", "10")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.size").value(10))

        val conditionCaptor = ArgumentCaptor.forClass(AdminAuditLogSearchCondition::class.java)
        val pageableCaptor = ArgumentCaptor.forClass(Pageable::class.java)

        verify(auditLogQueryService).searchAuditLogs(conditionCaptor.capture(), pageableCaptor.capture())

        val captured = conditionCaptor.value
        assertThat(captured.actorMemberId).isEqualTo(5L)
        assertThat(captured.action).isEqualTo(AuditAction.MODIFY_SPACE)
        assertThat(captured.targetType).isEqualTo(AuditTargetType.SPACE)
        assertThat(captured.targetId).isEqualTo(50L)
        assertThat(captured.dateFrom).isEqualTo(LocalDate.of(2026, 9, 10))
        assertThat(captured.dateTo).isEqualTo(LocalDate.of(2026, 9, 20))

        assertThat(pageableCaptor.value.pageNumber).isEqualTo(2)
        assertThat(pageableCaptor.value.pageSize).isEqualTo(10)
    }

    @Test
    @DisplayName("잘못된 enum 값이 전달되면 400 Bad Request와 VALIDATION_FAILED를 반환한다")
    fun getAuditLogs_invalidEnum_returns400() {
        mockMvc
            .perform(
                get("/api/v1/admin/audit-logs")
                    .param("action", "INVALID_ACTION_ENUM")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(auditLogQueryService)
    }

    @Test
    @DisplayName("잘못된 날짜 형식이 전달되면 400 Bad Request와 VALIDATION_FAILED를 반환한다")
    fun getAuditLogs_invalidDateFormat_returns400() {
        mockMvc
            .perform(
                get("/api/v1/admin/audit-logs")
                    .param("dateFrom", "not-a-date")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(auditLogQueryService)
    }
}
