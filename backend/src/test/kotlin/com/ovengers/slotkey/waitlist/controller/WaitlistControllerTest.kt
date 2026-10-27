package com.ovengers.slotkey.waitlist.controller

import com.fasterxml.jackson.databind.ObjectMapper
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
import com.ovengers.slotkey.waitlist.dto.request.WaitlistApplyRequest
import com.ovengers.slotkey.waitlist.dto.response.WaitlistEntryResponse
import com.ovengers.slotkey.waitlist.entity.WaitlistStatus
import com.ovengers.slotkey.waitlist.service.WaitlistService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * 대기 신청 API 웹 계층 테스트. 토큰의 회원 id가 서비스로 그대로 넘어가는지(소유권의 출발점)와
 * 응답 코드/형태를 검증한다. 규칙 검증은 WaitlistServiceIntegrationTest가 담당한다.
 */
@WebMvcTest(WaitlistController::class)
@ActiveProfiles("test")
@Import(
    SecurityConfig::class,
    ClockConfig::class,
    JwtProvider::class,
    WebMvcConfig::class,
    CurrentMemberArgumentResolver::class,
    GlobalExceptionHandler::class,
)
@DisplayName("대기 신청 컨트롤러 웹 계층 테스트")
class WaitlistControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var waitlistService: WaitlistService

    @MockBean
    private lateinit var authSessionService: AuthSessionService

    @MockBean
    private lateinit var inactiveMemberAccessLogService: InactiveMemberAccessLogService

    @BeforeEach
    fun allowTestSession() {
        org.mockito.BDDMockito
            .given(
                authSessionService.validate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()),
            ).willReturn(AuthSessionService.ValidationResult.VALID)
    }

    @Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    private fun userToken(): String =
        JwtUtil.createToken(
            secretKey,
            300_000L,
            mapOf("id" to MEMBER_ID, "email" to "user@slotkey.test", "role" to MemberRole.USER.name, "sid" to "test-session"),
        )

    private fun response(
        id: Long,
        position: Long,
    ): WaitlistEntryResponse =
        WaitlistEntryResponse(
            id,
            3L,
            START_TIME,
            END_TIME,
            WaitlistStatus.WAITING,
            position,
            LocalDateTime.of(2026, 10, 15, 9, 0),
        )

    @Test
    @DisplayName("신청: 201 CREATED와 신청 순서를 반환하고 토큰의 회원 id로 서비스를 호출한다")
    fun apply_returns201() {
        given(waitlistService.apply(MEMBER_ID, 3L, START_TIME, END_TIME)).willReturn(response(11L, 2L))

        mockMvc
            .perform(
                post("/api/v1/waitlist")
                    .header("Authorization", "Bearer " + userToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            WaitlistApplyRequest(
                                3L,
                                START_TIME,
                                END_TIME,
                            ),
                        ),
                    ),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.id").value(11L))
            .andExpect(jsonPath("$.data.status").value("WAITING"))
            .andExpect(jsonPath("$.data.position").value(2))

        verify(waitlistService).apply(MEMBER_ID, 3L, START_TIME, END_TIME)
    }

    @Test
    @DisplayName("신청: spaceId가 없으면 400 VALIDATION_FAILED이고 서비스를 호출하지 않는다")
    fun apply_missingSpaceId_returns400() {
        mockMvc
            .perform(
                post("/api/v1/waitlist")
                    .header("Authorization", "Bearer " + userToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"startTime\":\"2026-10-20T14:00:00\",\"endTime\":\"2026-10-20T15:00:00\"}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(waitlistService)
    }

    @Test
    @DisplayName("신청: 토큰이 없으면 401이고 서비스를 호출하지 않는다")
    fun apply_noToken_returns401() {
        mockMvc
            .perform(
                post("/api/v1/waitlist")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            WaitlistApplyRequest(
                                3L,
                                START_TIME,
                                END_TIME,
                            ),
                        ),
                    ),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))

        verifyNoInteractions(waitlistService)
    }

    @Test
    @DisplayName("취소: 204 NO_CONTENT를 반환하고 토큰의 회원 id로 서비스를 호출한다")
    fun cancel_returns204() {
        mockMvc
            .perform(
                delete("/api/v1/waitlist/{id}", 11L)
                    .header("Authorization", "Bearer " + userToken()),
            ).andExpect(status().isNoContent())

        verify(waitlistService).cancel(MEMBER_ID, 11L)
    }

    @Test
    @DisplayName("취소: 타인의 대기면 서비스의 FORBIDDEN_NOT_OWNER가 403으로 전달된다")
    fun cancel_notOwner_returns403() {
        willThrow(BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER))
            .given(waitlistService)
            .cancel(eq(MEMBER_ID), eq(11L))

        mockMvc
            .perform(
                delete("/api/v1/waitlist/{id}", 11L)
                    .header("Authorization", "Bearer " + userToken()),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_NOT_OWNER"))
    }

    @Test
    @DisplayName("내 목록: 200 OK와 신청 순서 목록을 반환한다")
    fun getMyEntries_returns200() {
        given(waitlistService.getMyEntries(MEMBER_ID))
            .willReturn(listOf(response(11L, 1L), response(12L, 3L)))

        mockMvc
            .perform(
                get("/api/v1/waitlist/me")
                    .header("Authorization", "Bearer " + userToken()),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[0].id").value(11L))
            .andExpect(jsonPath("$.data[0].position").value(1))
            .andExpect(jsonPath("$.data[1].position").value(3))

        verify(waitlistService).getMyEntries(MEMBER_ID)
    }

    private companion object {
        private const val MEMBER_ID = 7L
        private val START_TIME = LocalDateTime.of(2026, 10, 20, 14, 0)
        private val END_TIME = LocalDateTime.of(2026, 10, 20, 15, 0)
    }
}
