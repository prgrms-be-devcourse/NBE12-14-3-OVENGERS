@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.member.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.config.SecurityConfig
import com.ovengers.slotkey.global.error.GlobalExceptionHandler
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.global.security.jwt.JwtUtil
import com.ovengers.slotkey.member.authorization.MemberAuthorizationService
import com.ovengers.slotkey.member.dto.response.AdminMemberResponse
import com.ovengers.slotkey.member.dto.response.MemberStatusChangeResponse
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.service.AdminMemberService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(AdminMemberController::class)
@ActiveProfiles("test")
@Import(SecurityConfig::class, ClockConfig::class, JwtProvider::class, MemberAuthorizationService::class, GlobalExceptionHandler::class)
@DisplayName("관리자 회원 컨트롤러 웹 계층 테스트")
internal class AdminMemberControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var adminMemberService: AdminMemberService

    @MockBean
    private lateinit var authSessionService: AuthSessionService

    @MockBean
    private lateinit var inactiveMemberAccessLogService: InactiveMemberAccessLogService

    @BeforeEach fun allowTestSession() {
        whenever(authSessionService.validate(any<String>(), any<Long>()))
            .thenReturn(AuthSessionService.ValidationResult.VALID)
    }

    @Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    private fun createToken(
        id: Long,
        email: String,
        role: MemberRole,
    ): String =
        JwtUtil.createToken(
            secretKey,
            300000L,
            mapOf("id" to id, "email" to email, "role" to role.name, "sid" to "test-session"),
        )

    private fun createMemberResponse(
        memberId: Long,
        email: String,
        status: MemberStatus,
    ): AdminMemberResponse =
        AdminMemberResponse(
            memberId,
            email,
            "테스트유저",
            MemberRole.USER,
            status,
            5000,
            LocalDateTime.now(),
            null,
            null,
        )

    @Test
    @DisplayName("목록 조회: ADMIN 권한 토큰은 200 OK를 반환한다")
    fun getMembers_adminRole_returns200() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        val page: Page<AdminMemberResponse> = PageImpl(listOf(createMemberResponse(10L, "u10@test.com", MemberStatus.ACTIVE)))
        whenever(
            adminMemberService.getMembers(anyOrNull(), anyOrNull(), any()),
        ).thenReturn(page)

        mockMvc
            .perform(
                get("/api/v1/admin/members")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
    }

    @Test
    @DisplayName("목록 조회: USER 권한 토큰은 403 ACCESS_DENIED를 반환하고 서비스를 호출하지 않는다")
    fun getMembers_userRole_returns403() {
        val userToken: String = createToken(2L, "user@test.com", MemberRole.USER)

        mockMvc
            .perform(
                get("/api/v1/admin/members")
                    .header("Authorization", "Bearer $userToken"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        verifyNoInteractions(adminMemberService)
    }

    @Test
    @DisplayName("목록 조회: 토큰 없음은 401 AUTHENTICATION_REQUIRED를 반환하고 서비스를 호출하지 않는다")
    fun getMembers_noToken_returns401() {
        mockMvc
            .perform(get("/api/v1/admin/members"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))

        verifyNoInteractions(adminMemberService)
    }

    @Test
    @DisplayName("목록 조회: 잘못된 토큰은 401 INVALID_ACCESS_TOKEN을 반환하고 서비스를 호출하지 않는다")
    fun getMembers_invalidToken_returns401() {
        mockMvc
            .perform(
                get("/api/v1/admin/members")
                    .header("Authorization", "Bearer invalid.jwt.token"),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))

        verifyNoInteractions(adminMemberService)
    }

    @Test
    @DisplayName("목록 조회: 기본 요청(?page=0&size=20)은 status=null, keyword=null 및 페이지 메타데이터를 반환한다")
    fun getMembers_defaultRequest_passesNullFiltersAndReturnsPageResponse() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        val pageable: Pageable = PageRequest.of(0, 20)
        val page: Page<AdminMemberResponse> =
            PageImpl(
                listOf(createMemberResponse(10L, "u10@test.com", MemberStatus.ACTIVE)),
                pageable,
                1L,
            )
        whenever(adminMemberService.getMembers(eq(null), eq(null), any<Pageable>())).thenReturn(page)

        mockMvc
            .perform(
                get("/api/v1/admin/members?page=0&size=20")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.content[0].memberId").value(10L))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.totalPages").value(1))

        val captor = argumentCaptor<Pageable>()
        verify(adminMemberService).getMembers(eq(null), eq(null), captor.capture())
        assertThat(captor.firstValue.pageNumber).isEqualTo(0)
        assertThat(captor.firstValue.pageSize).isEqualTo(20)
    }

    @Test
    @DisplayName("목록 조회: 필터 요청(?status=ACTIVE&keyword=sample&page=1&size=5)이 서비스에 올바르게 전달된다")
    fun getMembers_filterRequest_passesFiltersCorrectly() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        val pageable: Pageable = PageRequest.of(1, 5)
        val page: Page<AdminMemberResponse> = PageImpl(emptyList(), pageable, 0L)
        whenever(adminMemberService.getMembers(eq(MemberStatus.ACTIVE), eq("sample"), any<Pageable>())).thenReturn(page)

        mockMvc
            .perform(
                get("/api/v1/admin/members?status=ACTIVE&keyword=sample&page=1&size=5")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(5))

        val captor = argumentCaptor<Pageable>()
        verify(adminMemberService).getMembers(eq(MemberStatus.ACTIVE), eq("sample"), captor.capture())
        assertThat(captor.firstValue.pageNumber).isEqualTo(1)
        assertThat(captor.firstValue.pageSize).isEqualTo(5)
    }

    @Test
    @DisplayName("목록 조회: 페이지 파라미터 생략 시 기본값 page 0, size 20이 전달된다")
    fun getMembers_omittedPaging_usesDefaultPageAndSize() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        whenever(adminMemberService.getMembers(eq(null), eq(null), any<Pageable>()))
            .thenReturn(Page.empty())

        mockMvc
            .perform(
                get("/api/v1/admin/members")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())

        val captor = argumentCaptor<Pageable>()
        verify(adminMemberService).getMembers(eq(null), eq(null), captor.capture())
        assertThat(captor.firstValue.pageNumber).isEqualTo(0)
        assertThat(captor.firstValue.pageSize).isEqualTo(20)
    }

    @Test
    @DisplayName("목록 조회: 빈 Page 반환 시 HTTP 200과 빈 content 배열을 반환한다")
    fun getMembers_emptyPage_returns200WithEmptyContent() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        whenever(adminMemberService.getMembers(eq(null), eq(null), any<Pageable>()))
            .thenReturn(PageImpl(emptyList(), PageRequest.of(0, 20), 0L))

        mockMvc
            .perform(
                get("/api/v1/admin/members")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isEmpty())
            .andExpect(jsonPath("$.data.totalElements").value(0))
    }

    @Test
    @DisplayName("회원 정지: memberId 42L, 사유, 토큰의 관리자 ID가 서비스에 올바르게 전달된다")
    fun suspend_passesPathAndBodyCorrectly() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        val response: MemberStatusChangeResponse =
            MemberStatusChangeResponse(
                42L,
                MemberStatus.SUSPENDED,
                "바인딩 검증",
                LocalDateTime.now(),
            )
        whenever(adminMemberService.suspend(eq(42L), eq("바인딩 검증"), eq(1L))).thenReturn(response)

        mockMvc
            .perform(
                patch("/api/v1/admin/members/42/suspend")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"바인딩 검증\"}"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.memberId").value(42L))
            .andExpect(jsonPath("$.data.status").value("SUSPENDED"))

        verify(adminMemberService).suspend(42L, "바인딩 검증", 1L)
    }

    @Test
    @DisplayName("회원 복구: memberId 42L, 사유, 토큰의 관리자 ID가 서비스에 올바르게 전달된다")
    fun restore_passesPathAndBodyCorrectly() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        val response: MemberStatusChangeResponse =
            MemberStatusChangeResponse(
                42L,
                MemberStatus.ACTIVE,
                "바인딩 검증",
                LocalDateTime.now(),
            )
        whenever(adminMemberService.restore(eq(42L), eq("바인딩 검증"), eq(1L))).thenReturn(response)

        mockMvc
            .perform(
                patch("/api/v1/admin/members/42/restore")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"바인딩 검증\"}"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.memberId").value(42L))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))

        verify(adminMemberService).restore(42L, "바인딩 검증", 1L)
    }

    @Test
    @DisplayName("크레딧 지급: memberId 42L, 금액, 사유, 토큰의 관리자 ID가 서비스에 올바르게 전달된다")
    fun grantCredit_passesPathAndBodyCorrectly() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        val response: AdminMemberResponse = createMemberResponse(42L, "u42@test.com", MemberStatus.ACTIVE)
        whenever(adminMemberService.grantCredit(eq(42L), eq(1000), eq("바인딩 검증"), eq(1L))).thenReturn(response)

        mockMvc
            .perform(
                post("/api/v1/admin/members/42/credits")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":1000,\"reason\":\"바인딩 검증\"}"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.memberId").value(42L))

        verify(adminMemberService).grantCredit(42L, 1000, "바인딩 검증", 1L)
    }
}
