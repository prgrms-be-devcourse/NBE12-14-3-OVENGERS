package com.ovengers.slotkey.inquiry.controller

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
import com.ovengers.slotkey.inquiry.dto.request.InquiryAnswerRequest
import com.ovengers.slotkey.inquiry.entity.Inquiry
import com.ovengers.slotkey.inquiry.entity.InquiryStatus
import com.ovengers.slotkey.inquiry.service.AdminInquiryService
import com.ovengers.slotkey.member.entity.MemberRole
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
import org.springframework.data.domain.Sort
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(AdminInquiryController::class)
@ActiveProfiles("test")
@Import(
    SecurityConfig::class,
    ClockConfig::class,
    JwtProvider::class,
    WebMvcConfig::class,
    CurrentMemberArgumentResolver::class,
    GlobalExceptionHandler::class,
)
@DisplayName("관리자 문의 컨트롤러 웹 계층 테스트")
internal class AdminInquiryControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var adminInquiryService: AdminInquiryService

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

    private fun createInquiry(
        id: Long,
        memberId: Long,
        status: InquiryStatus,
    ): Inquiry {
        val inquiry = Inquiry.create(memberId, "테스트 문의 제목", "테스트 문의 내용")
        ReflectionTestUtils.setField(inquiry, "id", id)
        if (status == InquiryStatus.ANSWERED) {
            inquiry.answer(1L, "테스트 답변", FIXED_TIME)
        }
        ReflectionTestUtils.setField(inquiry, "createdAt", FIXED_TIME)
        return inquiry
    }

    companion object {
        private val FIXED_TIME: LocalDateTime = LocalDateTime.of(2026, 10, 1, 12, 0)
    }

    @Test
    @DisplayName("목록 조회: ADMIN 권한 토큰은 200 OK를 반환한다")
    fun getInquiries_adminRole_returns200() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        val page: Page<Inquiry> = PageImpl(listOf(createInquiry(1L, 10L, InquiryStatus.WAITING)))
        whenever(adminInquiryService.getInquiries(anyOrNull(), any())).thenReturn(page)

        mockMvc
            .perform(
                get("/api/v1/admin/inquiries")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
    }

    @Test
    @DisplayName("목록 조회: USER 권한 토큰은 403 ACCESS_DENIED를 반환하고 서비스를 호출하지 않는다")
    fun getInquiries_userRole_returns403() {
        val userToken: String = createToken(2L, "user@test.com", MemberRole.USER)

        mockMvc
            .perform(
                get("/api/v1/admin/inquiries")
                    .header("Authorization", "Bearer $userToken"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        verifyNoInteractions(adminInquiryService)
    }

    @Test
    @DisplayName("목록 조회: 토큰 없음은 401 AUTHENTICATION_REQUIRED를 반환하고 서비스를 호출하지 않는다")
    fun getInquiries_noToken_returns401() {
        mockMvc
            .perform(get("/api/v1/admin/inquiries"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))

        verifyNoInteractions(adminInquiryService)
    }

    @Test
    @DisplayName("목록 조회: 잘못된 토큰은 401 INVALID_ACCESS_TOKEN을 반환하고 서비스를 호출하지 않는다")
    fun getInquiries_invalidToken_returns401() {
        mockMvc
            .perform(
                get("/api/v1/admin/inquiries")
                    .header("Authorization", "Bearer invalid.jwt.token"),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))

        verifyNoInteractions(adminInquiryService)
    }

    @Test
    @DisplayName("목록 조회: 기본 요청(?page=0&size=10&sort=createdAt,desc)은 status=null, page 0, size 10, 정렬 및 페이지 메타데이터를 반환한다")
    fun getInquiries_defaultRequest_passesNullFilterAndPaging() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        val pageable: Pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))
        val page: Page<Inquiry> =
            PageImpl(
                listOf(createInquiry(7L, 10L, InquiryStatus.WAITING)),
                pageable,
                1L,
            )
        whenever(adminInquiryService.getInquiries(eq(null), any<Pageable>())).thenReturn(page)

        mockMvc
            .perform(
                get("/api/v1/admin/inquiries?page=0&size=10&sort=createdAt,desc")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.content[0].id").value(7L))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(10))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.totalPages").value(1))

        val captor = argumentCaptor<Pageable>()
        verify(adminInquiryService).getInquiries(eq(null), captor.capture())
        assertThat(captor.firstValue.pageNumber).isEqualTo(0)
        assertThat(captor.firstValue.pageSize).isEqualTo(10)
        assertThat(captor.firstValue.sort.getOrderFor("createdAt")).isNotNull()
        assertThat(
            captor.firstValue
                .sort
                .getOrderFor("createdAt")
                ?.direction,
        ).isEqualTo(Sort.Direction.DESC)
    }

    @Test
    @DisplayName("목록 조회: 필터 요청(?status=WAITING&page=1&size=5&sort=createdAt,desc)이 서비스에 올바르게 전달된다")
    fun getInquiries_filterRequest_passesStatusAndPaging() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        val pageable: Pageable = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "createdAt"))
        val page: Page<Inquiry> = PageImpl(emptyList(), pageable, 0L)
        whenever(adminInquiryService.getInquiries(eq(InquiryStatus.WAITING), any<Pageable>())).thenReturn(page)

        mockMvc
            .perform(
                get("/api/v1/admin/inquiries?status=WAITING&page=1&size=5&sort=createdAt,desc")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(5))

        val captor = argumentCaptor<Pageable>()
        verify(adminInquiryService).getInquiries(eq(InquiryStatus.WAITING), captor.capture())
        assertThat(captor.firstValue.pageNumber).isEqualTo(1)
        assertThat(captor.firstValue.pageSize).isEqualTo(5)
    }

    @Test
    @DisplayName("목록 조회: 페이지 파라미터 생략 시 기본값 page 0, size 20이 전달된다")
    fun getInquiries_omittedPaging_usesDefaultPageAndSize() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        whenever(adminInquiryService.getInquiries(eq(null), any<Pageable>()))
            .thenReturn(Page.empty())

        mockMvc
            .perform(
                get("/api/v1/admin/inquiries")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())

        val captor = argumentCaptor<Pageable>()
        verify(adminInquiryService).getInquiries(eq(null), captor.capture())
        assertThat(captor.firstValue.pageNumber).isEqualTo(0)
        assertThat(captor.firstValue.pageSize).isEqualTo(20)
    }

    @Test
    @DisplayName("목록 조회: 빈 Page 반환 시 HTTP 200과 빈 content 배열을 반환한다")
    fun getInquiries_emptyPage_returns200WithEmptyContent() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        whenever(adminInquiryService.getInquiries(eq(null), any<Pageable>()))
            .thenReturn(PageImpl(emptyList(), PageRequest.of(0, 20), 0L))

        mockMvc
            .perform(
                get("/api/v1/admin/inquiries")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isEmpty())
            .andExpect(jsonPath("$.data.totalElements").value(0))
    }

    @Test
    @DisplayName("문의 상세: inquiryId 7L이 서비스에 전달되고 200 OK와 상세 응답을 반환한다")
    fun getInquiry_passesIdCorrectly() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        val inquiry: Inquiry = createInquiry(7L, 10L, InquiryStatus.WAITING)
        whenever(adminInquiryService.getInquiry(7L)).thenReturn(inquiry)

        mockMvc
            .perform(
                get("/api/v1/admin/inquiries/7")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(7L))
            .andExpect(jsonPath("$.data.status").value("WAITING"))

        verify(adminInquiryService).getInquiry(7L)
    }

    @Test
    @DisplayName("문의 상세: 존재하지 않는 문의 조회 시 HTTP 404 INQUIRY_NOT_FOUND를 반환한다")
    fun getInquiry_notFound_returns404() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        whenever(adminInquiryService.getInquiry(999L))
            .thenThrow(BusinessException(ErrorCode.INQUIRY_NOT_FOUND))

        mockMvc
            .perform(
                get("/api/v1/admin/inquiries/999")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"))

        verify(adminInquiryService).getInquiry(999L)
    }

    @Test
    @DisplayName("문의 답변: inquiryId 7L, 관리자 ID 1L, 답변 본문이 서비스에 전달된다")
    fun answer_passesParamsAndPrincipalCorrectly() {
        val adminToken: String = createToken(1L, "admin@test.com", MemberRole.ADMIN)
        val answered = createInquiry(7L, 10L, InquiryStatus.ANSWERED)
        whenever(adminInquiryService.answer(eq(7L), eq(1L), any<InquiryAnswerRequest>()))
            .thenReturn(answered)

        mockMvc
            .perform(
                post("/api/v1/admin/inquiries/7/answer")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"테스트 답변\"}"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(7L))
            .andExpect(jsonPath("$.data.status").value("ANSWERED"))
            .andExpect(jsonPath("$.data.answerContent").value("테스트 답변"))

        val captor = argumentCaptor<InquiryAnswerRequest>()
        verify(adminInquiryService).answer(eq(7L), eq(1L), captor.capture())
        assertThat(captor.firstValue.content).isEqualTo("테스트 답변")
    }
}
