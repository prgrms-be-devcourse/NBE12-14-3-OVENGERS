package com.ovengers.slotkey.audit.controller

import com.ovengers.slotkey.audit.dto.AdminAuditLogSearchCondition
import com.ovengers.slotkey.audit.dto.AuditLogResponse
import com.ovengers.slotkey.audit.service.AuditLogQueryService
import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.common.response.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "관리자 - 감사 로그", description = "관리자 작업 이력 조회 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
class AdminAuditLogController(
    private val auditLogQueryService: AuditLogQueryService,
) {
    @Operation(
        summary = "감사 로그 조회",
        description = """
            관리자 작업 이력을 검색 조건에 따라 조회합니다.
            조건을 생략하면 전체 목록을 조회하며, 여러 조건은 AND로 결합합니다.
            dateTo는 해당 날짜 전체를 포함합니다.
            정렬은 createdAt 내림차순, 같은 시각이면 id 내림차순으로 고정됩니다.
            page는 0부터 시작하며, 기본 페이지 크기는 20입니다.
            """,
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "감사 로그 조회 성공",
                useReturnTypeSchema = true,
            ),
            SwaggerApiResponse(
                responseCode = "400",
                description = "검색 조건 또는 날짜 형식 오류",
                content = [Content()],
            ),
            SwaggerApiResponse(
                responseCode = "401",
                description = "인증 정보가 없거나 액세스 토큰이 유효하지 않음",
                content = [Content()],
            ),
            SwaggerApiResponse(
                responseCode = "403",
                description = "관리자 권한이 없음",
                content = [Content()],
            ),
        ],
    )
    @GetMapping
    fun getAuditLogs(
        @ParameterObject
        @Valid
        @ModelAttribute
        condition: AdminAuditLogSearchCondition,
        @ParameterObject
        @PageableDefault(size = 20)
        pageable: Pageable,
    ): ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> {
        val response = PageResponse.from(auditLogQueryService.searchAuditLogs(condition, pageable))
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}
