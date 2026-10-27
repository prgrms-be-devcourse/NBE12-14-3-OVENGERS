package com.ovengers.slotkey.global.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class InactiveMemberAccessFilterTest {
    @Mock
    private lateinit var jwtProvider: JwtProvider

    @Mock
    private lateinit var authSessionService: AuthSessionService

    @Mock
    private lateinit var inactiveMemberAccessLogService: InactiveMemberAccessLogService

    @Mock
    private lateinit var filterChain: FilterChain

    private val objectMapper = ObjectMapper()
    private lateinit var filter: CustomAuthenticationFilter

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.clearContext()
        filter =
            CustomAuthenticationFilter(
                jwtProvider = jwtProvider,
                objectMapper = objectMapper,
                authSessionService = authSessionService,
                inactiveMemberAccessLogService = inactiveMemberAccessLogService,
                clock = CLOCK,
            )
    }

    @Test
    @DisplayName("비활성 회원의 출입 검증 요청은 로그를 기록하고 401로 차단한다")
    fun shouldRecordAndRejectInactiveMemberAccess() {
        givenSessionResult(AuthSessionService.ValidationResult.MEMBER_INACTIVE)
        val request = createRequest("POST", "/api/v1/door-access/verify")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertUnauthorized(response)
        verify(inactiveMemberAccessLogService).record(MEMBER_ID, LocalDateTime.now(CLOCK))
        verifyNoInteractions(filterChain)
    }

    @Test
    @DisplayName("출입 로그 저장이 실패해도 비활성 회원의 출입은 차단한다")
    fun shouldRejectAccessWhenLogRecordingFails() {
        givenSessionResult(AuthSessionService.ValidationResult.MEMBER_INACTIVE)
        doThrow(IllegalStateException("로그 저장 실패"))
            .`when`(inactiveMemberAccessLogService)
            .record(MEMBER_ID, LocalDateTime.now(CLOCK))
        val request = createRequest("POST", "/api/v1/door-access/verify")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertUnauthorized(response)
        assertThat(response.contentAsString).doesNotContain("로그 저장 실패")
        verifyNoInteractions(filterChain)
    }

    @Test
    @DisplayName("비활성 회원의 일반 조회 요청에는 출입 로그를 남기지 않는다")
    fun shouldNotRecordForGeneralRequest() {
        givenSessionResult(AuthSessionService.ValidationResult.MEMBER_INACTIVE)
        val request = createRequest("GET", "/api/v1/members/me")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertUnauthorized(response)
        verifyNoInteractions(inactiveMemberAccessLogService, filterChain)
    }

    @Test
    @DisplayName("비활성 회원의 출입키 발급 요청에는 출입 로그를 남기지 않는다")
    fun shouldNotRecordForTokenIssueRequest() {
        givenSessionResult(AuthSessionService.ValidationResult.MEMBER_INACTIVE)
        val request = createRequest("POST", "/api/v1/reservations/1/door-token")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertUnauthorized(response)
        verifyNoInteractions(inactiveMemberAccessLogService, filterChain)
    }

    @Test
    @DisplayName("JWT 검증에 실패한 출입 요청은 회원 비활성으로 기록하지 않는다")
    fun shouldNotRecordWhenJwtValidationFails() {
        given(jwtProvider.payloadOrNull(ACCESS_TOKEN)).willReturn(null)
        val request = createRequest("POST", "/api/v1/door-access/verify")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertUnauthorized(response)
        verifyNoInteractions(authSessionService, inactiveMemberAccessLogService, filterChain)
    }

    @Test
    @DisplayName("일반 세션 인증 실패는 회원 비활성으로 기록하지 않는다")
    fun shouldNotRecordWhenSessionIsInvalid() {
        givenSessionResult(AuthSessionService.ValidationResult.INVALID)
        val request = createRequest("POST", "/api/v1/door-access/verify")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertUnauthorized(response)
        verifyNoInteractions(inactiveMemberAccessLogService, filterChain)
    }

    private fun givenSessionResult(result: AuthSessionService.ValidationResult) {
        given(jwtProvider.payloadOrNull(ACCESS_TOKEN))
            .willReturn(
                mapOf(
                    "id" to MEMBER_ID,
                    "email" to "member@test.com",
                    "role" to "USER",
                    "sid" to SESSION_ID,
                    "exp" to CLOCK.instant().plusSeconds(300).epochSecond,
                ),
            )
        given(authSessionService.validate(SESSION_ID, MEMBER_ID)).willReturn(result)
    }

    private fun createRequest(
        method: String,
        path: String,
    ): MockHttpServletRequest =
        MockHttpServletRequest(method, path).apply {
            servletPath = path
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer $ACCESS_TOKEN")
        }

    private fun assertUnauthorized(response: MockHttpServletResponse) {
        assertThat(response.status).isEqualTo(401)
        val body = objectMapper.readTree(response.contentAsString)
        assertThat(body.path("status").asText()).isEqualTo("FAIL")
        assertThat(body.path("code").asText()).isEqualTo("INVALID_ACCESS_TOKEN")
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    private companion object {
        private const val MEMBER_ID = 1L
        private const val SESSION_ID = "test-session"
        private const val ACCESS_TOKEN = "test-access-token"
        private val CLOCK = Clock.fixed(Instant.parse("2026-10-19T07:00:00Z"), ZoneOffset.UTC)
    }
}
