package com.ovengers.slotkey.access.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.access.dto.request.DoorAccessVerifyRequest
import com.ovengers.slotkey.access.dto.response.DoorAccessLogResponse
import com.ovengers.slotkey.access.dto.response.DoorAccessTokenResponse
import com.ovengers.slotkey.access.dto.response.DoorAccessVerifyResponse
import com.ovengers.slotkey.access.entity.AccessDenyReason
import com.ovengers.slotkey.access.entity.AccessResult
import com.ovengers.slotkey.access.service.DoorAccessLogService
import com.ovengers.slotkey.access.service.DoorAccessTokenService
import com.ovengers.slotkey.access.service.DoorAccessVerificationService
import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.config.SecurityConfig
import com.ovengers.slotkey.global.config.WebMvcConfig
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.error.GlobalExceptionHandler
import com.ovengers.slotkey.global.security.CurrentMemberArgumentResolver
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.global.security.jwt.JwtUtil
import com.ovengers.slotkey.member.entity.MemberRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

/**
 * Java 출입 DTO 기준: c763cb8 + access Lombok 제거(생성자/getter 명시화) 작업 트리.
 * 실제 보안 필터·MVC·Jackson을 사용하고 DB 서비스 결과만 고정한다.
 * 공통 응답 골든을 복제하지 않고 출입 데이터와 해당 HTTP 경계를 검증한다.
 */
@WebMvcTest(DoorAccessController::class)
@ActiveProfiles("test")
@Import(
    SecurityConfig::class,
    ClockConfig::class,
    JwtProvider::class,
    WebMvcConfig::class,
    CurrentMemberArgumentResolver::class,
    GlobalExceptionHandler::class,
)
class DoorAccessGoldenTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    @MockBean
    private lateinit var doorAccessTokenService: DoorAccessTokenService

    @MockBean
    private lateinit var doorAccessLogService: DoorAccessLogService

    @MockBean
    private lateinit var doorAccessVerificationService: DoorAccessVerificationService

    @MockBean
    private lateinit var authSessionService: AuthSessionService

    @MockBean
    private lateinit var inactiveMemberAccessLogService: InactiveMemberAccessLogService

    private val attemptedAt = LocalDateTime.of(2026, 10, 23, 14, 5, 30)
    private lateinit var authorization: String

    @BeforeEach
    fun setUpAuthentication() {
        given(authSessionService.validate("access-golden-session", 1L))
            .willReturn(AuthSessionService.ValidationResult.VALID)
        authorization =
            "Bearer " +
            JwtUtil.createToken(
                secretKey,
                300_000L,
                mapOf(
                    "id" to 1L,
                    "email" to "access-golden@test.com",
                    "role" to MemberRole.USER.name,
                    "sid" to "access-golden-session",
                ),
            )
    }

    @Test
    @DisplayName("출입 토큰 발급의 201 응답은 ID·원문 토큰·발급 시각을 유지한다")
    fun issuedTokenMatchesGolden() {
        given(doorAccessTokenService.issue(1L, 101L))
            .willReturn(DoorAccessTokenResponse.of(101L, "fixed-door-token", attemptedAt))

        val result =
            mockMvc
                .perform(
                    post("/api/v1/reservations/101/door-token")
                        .header("Authorization", authorization),
                ).andExpect(status().isCreated)
                .andReturn()

        assertMatchesGolden(result, "token-issued.json")
        verify(doorAccessTokenService).issue(1L, 101L)
    }

    @Test
    @DisplayName("최초 체크인 허용 응답은 ALLOW·true·null 거절 사유를 유지한다")
    fun firstCheckInMatchesGolden() {
        given(doorAccessVerificationService.verify(eq(1L), any(DoorAccessVerifyRequest::class.java)))
            .willReturn(DoorAccessVerifyResponse.allow("회의실 A", true, attemptedAt))

        assertMatchesGolden(verifyDoor("""{"spaceId":10,"token":"fixed-door-token"}"""), "first-check-in-allowed.json")
        verify(doorAccessVerificationService).verify(
            eq(1L),
            argThat { request -> request.spaceId == 10L && request.token == "fixed-door-token" },
        )
    }

    @Test
    @DisplayName("재입장 허용 응답은 최초 체크인 여부 false를 유지한다")
    fun reentryMatchesGolden() {
        given(doorAccessVerificationService.verify(eq(1L), any(DoorAccessVerifyRequest::class.java)))
            .willReturn(DoorAccessVerifyResponse.allow("회의실 A", false, attemptedAt))

        assertMatchesGolden(verifyDoor("""{"spaceId":10,"token":"fixed-door-token"}"""), "reentry-allowed.json")
    }

    @Test
    @DisplayName("출입 거절은 HTTP 200이며 DENY·사유·중첩 null 필드를 유지한다")
    fun deniedAccessMatchesGolden() {
        given(doorAccessVerificationService.verify(eq(1L), any(DoorAccessVerifyRequest::class.java)))
            .willReturn(DoorAccessVerifyResponse.deny(AccessDenyReason.TOKEN_REVOKED, attemptedAt))

        assertMatchesGolden(verifyDoor("""{"spaceId":10,"token":"revoked-token"}"""), "access-denied.json")
    }

    @Test
    @DisplayName("출입 로그는 허용·거절 항목의 순서·시각·nullable 공간명을 유지한다")
    fun accessLogsMatchGolden() {
        given(doorAccessLogService.findResponsesByReservationId(1L, 101L)).willReturn(
            listOf(
                DoorAccessLogResponse(1002L, attemptedAt, "회의실 A", AccessResult.ALLOW, null),
                DoorAccessLogResponse(1001L, attemptedAt.minusMinutes(1), null, AccessResult.DENY, AccessDenyReason.SPACE_MISMATCH),
            ),
        )

        val result =
            mockMvc
                .perform(
                    get("/api/v1/reservations/101/access-logs").header("Authorization", authorization),
                ).andExpect(status().isOk)
                .andReturn()

        assertMatchesGolden(result, "access-logs.json")
    }

    @Test
    @DisplayName("출입 로그가 없으면 빈 배열을 유지한다")
    fun emptyAccessLogsMatchGolden() {
        given(doorAccessLogService.findResponsesByReservationId(1L, 101L)).willReturn(emptyList())

        val result =
            mockMvc
                .perform(
                    get("/api/v1/reservations/101/access-logs").header("Authorization", authorization),
                ).andExpect(status().isOk)
                .andReturn()

        assertMatchesGolden(result, "access-logs-empty.json")
    }

    @Test
    @DisplayName("공간 ID 누락·명시적 null은 같은 400 검증 오류이며 서비스를 호출하지 않는다")
    fun missingAndNullSpaceIdMatchGolden() {
        listOf("""{"token":"fixed-door-token"}""", """{"spaceId":null,"token":"fixed-door-token"}""")
            .forEach { body ->
                assertMatchesGolden(verifyDoor(body, 400), "space-id-required.json")
            }
        verifyNoInteractions(doorAccessVerificationService)
    }

    @Test
    @DisplayName("토큰 누락·null·빈 문자열·공백은 같은 400 검증 오류이며 서비스를 호출하지 않는다")
    fun missingNullAndBlankTokenMatchGolden() {
        listOf("""{"spaceId":10}""", """{"spaceId":10,"token":null}""", """{"spaceId":10,"token":""}""", """{"spaceId":10,"token":"   "}""")
            .forEach { body ->
                assertMatchesGolden(verifyDoor(body, 400), "token-required.json")
            }
        verifyNoInteractions(doorAccessVerificationService)
    }

    @Test
    @DisplayName("미인증 출입 요청은 401이며 서비스를 호출하지 않는다")
    fun unauthenticatedAccessMatchesGolden() {
        val result =
            mockMvc
                .perform(
                    post("/api/v1/door-access/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"spaceId":10,"token":"fixed-door-token"}"""),
                ).andExpect(status().isUnauthorized)
                .andReturn()

        assertMatchesGolden(result, "authentication-required.json")
        verifyNoInteractions(doorAccessVerificationService)
    }

    @Test
    @DisplayName("타인 예약 출입 로그 조회 거절은 기존 403 오류를 유지한다")
    fun forbiddenAccessLogsMatchGolden() {
        given(doorAccessLogService.findResponsesByReservationId(1L, 101L))
            .willThrow(BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER))

        val result =
            mockMvc
                .perform(
                    get("/api/v1/reservations/101/access-logs").header("Authorization", authorization),
                ).andExpect(status().isForbidden)
                .andReturn()

        assertMatchesGolden(result, "not-owner.json")
    }

    private fun verifyDoor(
        body: String,
        expectedStatus: Int = 200,
    ): MvcResult =
        mockMvc
            .perform(
                post("/api/v1/door-access/verify")
                    .header("Authorization", authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().`is`(expectedStatus))
            .andReturn()

    private fun assertMatchesGolden(
        result: MvcResult,
        filename: String,
    ) {
        val actual = objectMapper.readTree(result.response.getContentAsString(StandardCharsets.UTF_8))
        val golden =
            requireNotNull(javaClass.getResourceAsStream("/golden/access/$filename")) {
                "출입 골든 파일이 존재해야 한다: $filename"
            }
        assertThat(actual).isEqualTo(objectMapper.readTree(golden))
    }
}
