package com.ovengers.slotkey.audit.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.audit.dto.AuditLogResponse
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.service.AuditLogQueryService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.config.SecurityConfig
import com.ovengers.slotkey.global.config.WebMvcConfig
import com.ovengers.slotkey.global.error.GlobalExceptionHandler
import com.ovengers.slotkey.global.security.CurrentMemberArgumentResolver
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.global.security.jwt.JwtUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * #192 감사 도메인 대표 HTTP 본문 골든.
 *
 * 실제 Spring MVC 슬라이스(운영 ObjectMapper·GlobalExceptionHandler·SecurityConfig)를 사용한다.
 * 감사 서비스는 대역이므로 Repository/JPQL·MySQL 정렬은 검증하지 않는다(AuditLogRepositoryTest 등이 담당).
 * 정적 골든은 전환 전 Java 커밋 a380133에서 동일 입력/직렬화 경로로 캡처했다.
 */
@WebMvcTest(AdminAuditLogController::class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import(
    SecurityConfig::class,
    ClockConfig::class,
    JwtProvider::class,
    WebMvcConfig::class,
    CurrentMemberArgumentResolver::class,
    GlobalExceptionHandler::class,
)
class AuditHttpBodyGoldenTest {
    private companion object {
        const val GOLDEN_DIRECTORY = "/golden/audit/"
    }

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @MockBean
    private lateinit var auditLogQueryService: AuditLogQueryService

    @MockBean
    private lateinit var sessions: AuthSessionService

    @MockBean
    private lateinit var inactiveAccess: InactiveMemberAccessLogService

    @Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    @BeforeEach
    fun allowSession() {
        given(sessions.validate(anyString(), anyLong())).willReturn(AuthSessionService.ValidationResult.VALID)
    }

    @Test
    @DisplayName("GET /api/v1/admin/audit-logs 2건 페이지는 항목 순서·선택 null·원본 JSON 문자열을 포함한 Java 기준 HTTP 본문을 반환한다")
    fun auditLogPageHttpBodyMatchesJavaGolden() {
        val first =
            AuditLogResponse(
                100L,
                7L,
                AuditAction.MODIFY_SPACE,
                AuditTargetType.SPACE,
                11L,
                "가격 변경",
                "{\"pricePerSlot\":5000}",
                "{\"pricePerSlot\":6000}",
                LocalDateTime.of(2026, 10, 14, 10, 15, 30),
            )
        val second =
            AuditLogResponse(
                101L,
                null,
                AuditAction.FORCE_CANCEL_RESERVATION,
                AuditTargetType.RESERVATION,
                200L,
                null,
                null,
                null,
                LocalDateTime.of(2026, 10, 14, 10, 20, 0),
            )
        val page: Page<AuditLogResponse> =
            PageImpl(listOf(first, second), PageRequest.of(0, 20), 2L)
        given(auditLogQueryService.searchAuditLogs(any(), any())).willReturn(page)

        val body =
            mvc
                .perform(
                    get("/api/v1/admin/audit-logs")
                        .header("Authorization", adminAuthorization())
                        .contentType(MediaType.APPLICATION_JSON),
                ).andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsByteArray

        val actual = mapper.readTree(body)

        assertThat(actual.at("/data/content").isArray).isTrue()
        assertThat(actual.at("/data/content/0/id").asLong()).isEqualTo(100L)
        assertThat(actual.at("/data/content/1/id").asLong()).isEqualTo(101L)
        assertThat(actual.at("/data/totalElements").asLong()).isEqualTo(2L)
        assertThat(actual.at("/data/totalPages").asInt()).isEqualTo(1)
        // beforeValue/afterValue는 중첩 객체가 아니라 저장된 JSON 문자열 그대로다.
        assertThat(actual.at("/data/content/0/beforeValue").asText()).isEqualTo("{\"pricePerSlot\":5000}")
        assertThat(actual.at("/data/content/0/beforeValue").isObject).isFalse()
        // 선택 null 필드는 누락이 아니라 null로 유지된다.
        for (field in listOf("actorMemberId", "reason", "beforeValue", "afterValue")) {
            assertThat(actual.at("/data/content/1").has(field)).`as`("%s는 존재해야 한다", field).isTrue()
            assertThat(actual.at("/data/content/1/$field").isNull).`as`("%s는 null이어야 한다", field).isTrue()
        }

        assertThat(actual).isEqualTo(readGolden("audit-log-page-http-multi.json"))
    }

    @Test
    @DisplayName("인증 없는 감사 로그 조회는 SecurityFilterChain에서 401 AUTHENTICATION_REQUIRED로 차단된다")
    fun auditLogAnonymousRejectedBySecurityFilter() {
        mvc
            .perform(get("/api/v1/admin/audit-logs"))
            .andExpect(status().isUnauthorized)
            .andExpect { result ->
                assertThat(mapper.readTree(result.response.contentAsByteArray).get("code").asText())
                    .isEqualTo("AUTHENTICATION_REQUIRED")
            }
    }

    private fun adminAuthorization(): String =
        "Bearer " +
            JwtUtil.createToken(
                secretKey,
                300_000L,
                mapOf(
                    "id" to 1L,
                    "email" to "admin@slotkey.test",
                    "role" to "ADMIN",
                    "sid" to "admin-session",
                ),
            )

    private fun readGolden(file: String): JsonNode {
        val stream =
            javaClass.getResourceAsStream(GOLDEN_DIRECTORY + file)
                ?: throw AssertionError("골든 파일이 존재해야 한다: $file")
        return stream.use { mapper.readTree(it) }
    }
}
