@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.member.controller

import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.config.SecurityConfig
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.error.GlobalExceptionHandler
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.global.security.jwt.JwtUtil
import com.ovengers.slotkey.member.authorization.MemberAuthorizationService
import com.ovengers.slotkey.member.dto.response.AdminMemberCreditTransactionResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberDetailResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberReservationResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberStatusHistoryResponse
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.service.AdminMemberService
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(AdminMemberController::class)
@ActiveProfiles("test")
@Import(SecurityConfig::class, ClockConfig::class, JwtProvider::class, MemberAuthorizationService::class, GlobalExceptionHandler::class)
@DisplayName("관리자 회원 상세 컨트롤러 테스트")
internal class AdminMemberDetailControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

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
            LocalDateTime.of(2026, 10, 17, 10, 0),
            null,
            null,
        )

    @Test
    @DisplayName("회원 상세 조회: ADMIN 권한으로 조회하면 회원 정보와 요약 통계를 반환한다")
    fun getMemberDetail_admin_returns200() {
        // given
        val adminToken: String =
            createToken(1L, "admin@test.com", MemberRole.ADMIN)

        val member: AdminMemberResponse =
            createMemberResponse(
                42L,
                "user@test.com",
                MemberStatus.ACTIVE,
            )

        val response: AdminMemberDetailResponse =
            AdminMemberDetailResponse(member, 7L, 2L)

        whenever(adminMemberService.getMemberDetail(42L))
            .thenReturn(response)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/42")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.member.memberId").value(42))
            .andExpect(jsonPath("$.data.member.email").value("user@test.com"))
            .andExpect(jsonPath("$.data.member.balance").value(5000))
            .andExpect(jsonPath("$.data.totalReservationCount").value(7))
            .andExpect(jsonPath("$.data.suspensionCount").value(2))

        verify(adminMemberService).getMemberDetail(42L)
    }

    @Test
    @DisplayName("회원 상세 조회: 존재하지 않는 회원은 404 MEMBER_NOT_FOUND를 반환한다")
    fun getMemberDetail_notFound_returns404() {
        // given
        val adminToken: String =
            createToken(1L, "admin@test.com", MemberRole.ADMIN)

        whenever(adminMemberService.getMemberDetail(999L))
            .thenThrow(BusinessException(ErrorCode.MEMBER_NOT_FOUND))

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/999")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))

        verify(adminMemberService).getMemberDetail(999L)
    }

    @Test
    @DisplayName("회원 상세 조회: 일반 회원은 403 ACCESS_DENIED를 반환한다")
    fun getMemberDetail_userRole_returns403() {
        // given
        val userToken: String =
            createToken(2L, "user@test.com", MemberRole.USER)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/42")
                    .header("Authorization", "Bearer $userToken"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        verifyNoInteractions(adminMemberService)
    }

    @Test
    @DisplayName("회원 상세 조회: 토큰이 없으면 401 AUTHENTICATION_REQUIRED를 반환한다")
    fun getMemberDetail_noToken_returns401() {
        mockMvc
            .perform(get("/api/v1/admin/members/42"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))

        verifyNoInteractions(adminMemberService)
    }

    @Test
    @DisplayName("크레딧 내역 조회: ADMIN 권한으로 조회하면 거래 내역과 페이지 정보를 반환한다")
    fun getMemberCreditTransactions_admin_returns200() {
        // given
        val adminToken: String =
            createToken(1L, "admin@test.com", MemberRole.ADMIN)

        val pageable: Pageable = PageRequest.of(1, 5)

        val transaction: AdminMemberCreditTransactionResponse =
            AdminMemberCreditTransactionResponse(
                10L,
                CreditTransactionType.TOP_UP,
                50000,
                80000,
                null,
                LocalDateTime.of(2026, 10, 17, 15, 30),
            )

        val page: Page<AdminMemberCreditTransactionResponse> =
            PageImpl(
                listOf(transaction),
                pageable,
                6L,
            )

        whenever(
            adminMemberService.getMemberCreditTransactions(
                eq(42L),
                any<Pageable>(),
            ),
        ).thenReturn(page)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/42/credit-transactions")
                    .param("page", "1")
                    .param("size", "5")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.content[0].transactionId").value(10))
            .andExpect(jsonPath("$.data.content[0].type").value("TOP_UP"))
            .andExpect(jsonPath("$.data.content[0].amount").value(50000))
            .andExpect(jsonPath("$.data.content[0].balanceAfter").value(80000))
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(5))
            .andExpect(jsonPath("$.data.totalElements").value(6))
            .andExpect(jsonPath("$.data.totalPages").value(2))

        val captor = argumentCaptor<Pageable>()

        verify(adminMemberService)
            .getMemberCreditTransactions(eq(42L), captor.capture())

        assertThat(captor.firstValue.pageNumber).isEqualTo(1)
        assertThat(captor.firstValue.pageSize).isEqualTo(5)
    }

    @Test
    @DisplayName("크레딧 내역 조회: 존재하지 않는 회원은 404 MEMBER_NOT_FOUND를 반환한다")
    fun getMemberCreditTransactions_notFound_returns404() {
        // given
        val adminToken: String =
            createToken(1L, "admin@test.com", MemberRole.ADMIN)

        whenever(
            adminMemberService.getMemberCreditTransactions(
                eq(999L),
                any<Pageable>(),
            ),
        ).thenThrow(
            BusinessException(ErrorCode.MEMBER_NOT_FOUND),
        )

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/999/credit-transactions")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))

        verify(adminMemberService).getMemberCreditTransactions(
            eq(999L),
            any<Pageable>(),
        )
    }

    @Test
    @DisplayName("크레딧 내역 조회: 일반 회원은 403 ACCESS_DENIED를 반환한다")
    fun getMemberCreditTransactions_userRole_returns403() {
        // given
        val userToken: String =
            createToken(2L, "user@test.com", MemberRole.USER)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/42/credit-transactions")
                    .header("Authorization", "Bearer $userToken"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        verifyNoInteractions(adminMemberService)
    }

    @Test
    @DisplayName("크레딧 내역 조회: 토큰이 없으면 401 AUTHENTICATION_REQUIRED를 반환한다")
    fun getMemberCreditTransactions_noToken_returns401() {
        mockMvc
            .perform(get("/api/v1/admin/members/42/credit-transactions"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))

        verifyNoInteractions(adminMemberService)
    }

    @Test
    @DisplayName("예약 내역 조회: ADMIN 권한으로 조회하면 예약 정보와 페이지 정보를 반환한다")
    fun getMemberReservations_admin_returns200() {
        // given
        val adminToken: String =
            createToken(1L, "admin@test.com", MemberRole.ADMIN)

        val pageable: Pageable = PageRequest.of(1, 5)

        val reservation: AdminMemberReservationResponse =
            AdminMemberReservationResponse(
                102L,
                10L,
                "회의실 A",
                LocalDateTime.of(2026, 10, 20, 14, 0),
                LocalDateTime.of(2026, 10, 20, 15, 0),
                ReservationStatus.CONFIRMED,
                5000,
                LocalDateTime.of(2026, 10, 18, 10, 0),
            )

        val page: Page<AdminMemberReservationResponse> =
            PageImpl(
                listOf(reservation),
                pageable,
                6L,
            )

        whenever(
            adminMemberService.getMemberReservations(
                eq(42L),
                any<Pageable>(),
            ),
        ).thenReturn(page)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/42/reservations")
                    .param("page", "1")
                    .param("size", "5")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.content[0].reservationId").value(102))
            .andExpect(jsonPath("$.data.content[0].spaceId").value(10))
            .andExpect(jsonPath("$.data.content[0].spaceName").value("회의실 A"))
            .andExpect(jsonPath("$.data.content[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.content[0].totalAmount").value(5000))
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(5))
            .andExpect(jsonPath("$.data.totalElements").value(6))
            .andExpect(jsonPath("$.data.totalPages").value(2))

        val captor = argumentCaptor<Pageable>()

        verify(adminMemberService)
            .getMemberReservations(eq(42L), captor.capture())

        assertThat(captor.firstValue.pageNumber).isEqualTo(1)
        assertThat(captor.firstValue.pageSize).isEqualTo(5)
    }

    @Test
    @DisplayName("예약 내역 조회: 존재하지 않는 회원은 404 MEMBER_NOT_FOUND를 반환한다")
    fun getMemberReservations_notFound_returns404() {
        // given
        val adminToken: String =
            createToken(1L, "admin@test.com", MemberRole.ADMIN)

        whenever(
            adminMemberService.getMemberReservations(
                eq(999L),
                any<Pageable>(),
            ),
        ).thenThrow(BusinessException(ErrorCode.MEMBER_NOT_FOUND))

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/999/reservations")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))

        verify(adminMemberService).getMemberReservations(
            eq(999L),
            any<Pageable>(),
        )
    }

    @Test
    @DisplayName("예약 내역 조회: 일반 회원은 403 ACCESS_DENIED를 반환한다")
    fun getMemberReservations_userRole_returns403() {
        // given
        val userToken: String =
            createToken(2L, "user@test.com", MemberRole.USER)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/42/reservations")
                    .header("Authorization", "Bearer $userToken"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        verifyNoInteractions(adminMemberService)
    }

    @Test
    @DisplayName("예약 내역 조회: 토큰이 없으면 401 AUTHENTICATION_REQUIRED를 반환한다")
    fun getMemberReservations_noToken_returns401() {
        // when & then
        mockMvc
            .perform(get("/api/v1/admin/members/42/reservations"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))

        verifyNoInteractions(adminMemberService)
    }

    @Test
    @DisplayName("상태 변경 이력 조회: ADMIN 권한으로 조회하면 이력과 페이지 정보를 반환한다")
    fun getMemberStatusHistory_admin_returns200() {
        // given
        val adminToken: String =
            createToken(1L, "admin@test.com", MemberRole.ADMIN)

        val pageable: Pageable = PageRequest.of(1, 5)

        val history: AdminMemberStatusHistoryResponse =
            AdminMemberStatusHistoryResponse(
                12L,
                AuditAction.REACTIVATE_MEMBER,
                100L,
                "정지 사유 해소",
                "SUSPENDED",
                "ACTIVE",
                LocalDateTime.of(2026, 10, 18, 15, 0),
            )

        val page: Page<AdminMemberStatusHistoryResponse> =
            PageImpl(
                listOf(history),
                pageable,
                6L,
            )

        whenever(
            adminMemberService.getMemberStatusHistory(
                eq(42L),
                any<Pageable>(),
            ),
        ).thenReturn(page)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/42/status-history")
                    .param("page", "1")
                    .param("size", "5")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.content[0].historyId").value(12))
            .andExpect(
                jsonPath("$.data.content[0].action")
                    .value("REACTIVATE_MEMBER"),
            ).andExpect(
                jsonPath("$.data.content[0].actorMemberId")
                    .value(100),
            ).andExpect(
                jsonPath("$.data.content[0].reason")
                    .value("정지 사유 해소"),
            ).andExpect(
                jsonPath("$.data.content[0].beforeValue")
                    .value("SUSPENDED"),
            ).andExpect(
                jsonPath("$.data.content[0].afterValue")
                    .value("ACTIVE"),
            ).andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(5))
            .andExpect(jsonPath("$.data.totalElements").value(6))
            .andExpect(jsonPath("$.data.totalPages").value(2))

        val captor = argumentCaptor<Pageable>()

        verify(adminMemberService)
            .getMemberStatusHistory(eq(42L), captor.capture())

        assertThat(captor.firstValue.pageNumber).isEqualTo(1)
        assertThat(captor.firstValue.pageSize).isEqualTo(5)
    }

    @Test
    @DisplayName("상태 변경 이력 조회: 존재하지 않는 회원은 404 MEMBER_NOT_FOUND를 반환한다")
    fun getMemberStatusHistory_notFound_returns404() {
        // given
        val adminToken: String =
            createToken(1L, "admin@test.com", MemberRole.ADMIN)

        whenever(
            adminMemberService.getMemberStatusHistory(
                eq(999L),
                any<Pageable>(),
            ),
        ).thenThrow(
            BusinessException(ErrorCode.MEMBER_NOT_FOUND),
        )

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/999/status-history")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))

        verify(adminMemberService).getMemberStatusHistory(
            eq(999L),
            any<Pageable>(),
        )
    }

    @Test
    @DisplayName("상태 변경 이력 조회: 일반 회원은 403 ACCESS_DENIED를 반환한다")
    fun getMemberStatusHistory_userRole_returns403() {
        // given
        val userToken: String =
            createToken(2L, "user@test.com", MemberRole.USER)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/admin/members/42/status-history")
                    .header("Authorization", "Bearer $userToken"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        verifyNoInteractions(adminMemberService)
    }

    @Test
    @DisplayName("상태 변경 이력 조회: 토큰이 없으면 401 AUTHENTICATION_REQUIRED를 반환한다")
    fun getMemberStatusHistory_noToken_returns401() {
        // when & then
        mockMvc
            .perform(get("/api/v1/admin/members/42/status-history"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))

        verifyNoInteractions(adminMemberService)
    }
}
