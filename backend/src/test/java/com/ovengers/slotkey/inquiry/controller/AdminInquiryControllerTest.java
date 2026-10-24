package com.ovengers.slotkey.inquiry.controller;

import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.global.config.SecurityConfig;
import com.ovengers.slotkey.global.config.ClockConfig;
import com.ovengers.slotkey.auth.service.AuthSessionService;
import com.ovengers.slotkey.global.config.WebMvcConfig;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.global.error.GlobalExceptionHandler;
import com.ovengers.slotkey.global.security.CurrentMemberArgumentResolver;
import com.ovengers.slotkey.global.security.jwt.JwtProvider;
import com.ovengers.slotkey.global.security.jwt.JwtUtil;
import com.ovengers.slotkey.inquiry.dto.request.InquiryAnswerRequest;
import com.ovengers.slotkey.inquiry.entity.Inquiry;
import com.ovengers.slotkey.inquiry.entity.InquiryStatus;
import com.ovengers.slotkey.inquiry.service.AdminInquiryService;
import com.ovengers.slotkey.member.entity.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminInquiryController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, ClockConfig.class, JwtProvider.class, WebMvcConfig.class, CurrentMemberArgumentResolver.class, GlobalExceptionHandler.class})
@DisplayName("관리자 문의 컨트롤러 웹 계층 테스트")
class AdminInquiryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AdminInquiryService adminInquiryService;

    @MockBean
    private AuthSessionService authSessionService;

    @MockBean
    private InactiveMemberAccessLogService inactiveMemberAccessLogService;

    @BeforeEach
    void allowTestSession() {
        given(authSessionService.validate(anyString(), anyLong()))
                .willReturn(AuthSessionService.ValidationResult.VALID);
    }

    @Value("${custom.jwt.secret-key}")
    private String secretKey;

    private String createToken(Long id, String email, MemberRole role) {
        return JwtUtil.createToken(
                secretKey,
                300_000L,
                Map.of(
                        "id", id,
                        "email", email,
                        "role", role.name(),
                        "sid", "test-session"
                )
        );
    }

    private Inquiry createInquiry(Long id, Long memberId, InquiryStatus status) {
        Inquiry inquiry = Inquiry.create(
                memberId,
                "테스트 문의 제목",
                "테스트 문의 내용"
        );
        ReflectionTestUtils.setField(inquiry, "id", id);
        if (status == InquiryStatus.ANSWERED) {
            inquiry.answer(1L, "테스트 답변", LocalDateTime.of(2026, 10, 1, 12, 0));
        }
        ReflectionTestUtils.setField(
                inquiry,
                "createdAt",
                LocalDateTime.of(2026, 10, 1, 12, 0)
        );
        return inquiry;
    }

    @Test
    @DisplayName("목록 조회: ADMIN 권한 토큰은 200 OK를 반환한다")
    void getInquiries_adminRole_returns200() throws Exception {
        String adminToken = createToken(1L, "admin@test.com", MemberRole.ADMIN);
        Page<Inquiry> page = new PageImpl<>(List.of(createInquiry(1L, 10L, InquiryStatus.WAITING)));
        given(adminInquiryService.getInquiries(any(), any())).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/inquiries")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @Test
    @DisplayName("목록 조회: USER 권한 토큰은 403 ACCESS_DENIED를 반환하고 서비스를 호출하지 않는다")
    void getInquiries_userRole_returns403() throws Exception {
        String userToken = createToken(2L, "user@test.com", MemberRole.USER);

        mockMvc.perform(get("/api/v1/admin/inquiries")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(adminInquiryService);
    }

    @Test
    @DisplayName("목록 조회: 토큰 없음은 401 AUTHENTICATION_REQUIRED를 반환하고 서비스를 호출하지 않는다")
    void getInquiries_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/inquiries"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(adminInquiryService);
    }

    @Test
    @DisplayName("목록 조회: 잘못된 토큰은 401 INVALID_ACCESS_TOKEN을 반환하고 서비스를 호출하지 않는다")
    void getInquiries_invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/inquiries")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        verifyNoInteractions(adminInquiryService);
    }

    @Test
    @DisplayName("목록 조회: 기본 요청(?page=0&size=10&sort=createdAt,desc)은 status=null, page 0, size 10, 정렬 및 페이지 메타데이터를 반환한다")
    void getInquiries_defaultRequest_passesNullFilterAndPaging() throws Exception {
        String adminToken = createToken(1L, "admin@test.com", MemberRole.ADMIN);
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Inquiry> page = new PageImpl<>(
                List.of(createInquiry(7L, 10L, InquiryStatus.WAITING)),
                pageable,
                1L
        );
        given(adminInquiryService.getInquiries(eq(null), any(Pageable.class))).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/inquiries?page=0&size=10&sort=createdAt,desc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0].id").value(7L))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(adminInquiryService).getInquiries(eq(null), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(captor.getValue().getPageSize()).isEqualTo(10);
        assertThat(captor.getValue().getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(captor.getValue().getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("목록 조회: 필터 요청(?status=WAITING&page=1&size=5&sort=createdAt,desc)이 서비스에 올바르게 전달된다")
    void getInquiries_filterRequest_passesStatusAndPaging() throws Exception {
        String adminToken = createToken(1L, "admin@test.com", MemberRole.ADMIN);
        Pageable pageable = PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Inquiry> page = new PageImpl<>(List.of(), pageable, 0L);
        given(adminInquiryService.getInquiries(eq(InquiryStatus.WAITING), any(Pageable.class))).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/inquiries?status=WAITING&page=1&size=5&sort=createdAt,desc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(5));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(adminInquiryService).getInquiries(eq(InquiryStatus.WAITING), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("목록 조회: 페이지 파라미터 생략 시 기본값 page 0, size 20이 전달된다")
    void getInquiries_omittedPaging_usesDefaultPageAndSize() throws Exception {
        String adminToken = createToken(1L, "admin@test.com", MemberRole.ADMIN);
        given(adminInquiryService.getInquiries(eq(null), any(Pageable.class)))
                .willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/admin/inquiries")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(adminInquiryService).getInquiries(eq(null), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("목록 조회: 빈 Page 반환 시 HTTP 200과 빈 content 배열을 반환한다")
    void getInquiries_emptyPage_returns200WithEmptyContent() throws Exception {
        String adminToken = createToken(1L, "admin@test.com", MemberRole.ADMIN);
        given(adminInquiryService.getInquiries(eq(null), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L));

        mockMvc.perform(get("/api/v1/admin/inquiries")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("문의 상세: inquiryId 7L이 서비스에 전달되고 200 OK와 상세 응답을 반환한다")
    void getInquiry_passesIdCorrectly() throws Exception {
        String adminToken = createToken(1L, "admin@test.com", MemberRole.ADMIN);
        Inquiry inquiry = createInquiry(7L, 10L, InquiryStatus.WAITING);
        given(adminInquiryService.getInquiry(7L)).willReturn(inquiry);

        mockMvc.perform(get("/api/v1/admin/inquiries/7")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(7L))
                .andExpect(jsonPath("$.data.status").value("WAITING"));

        verify(adminInquiryService).getInquiry(7L);
    }

    @Test
    @DisplayName("문의 상세: 존재하지 않는 문의 조회 시 HTTP 404 INQUIRY_NOT_FOUND를 반환한다")
    void getInquiry_notFound_returns404() throws Exception {
        String adminToken = createToken(1L, "admin@test.com", MemberRole.ADMIN);
        given(adminInquiryService.getInquiry(999L))
                .willThrow(new BusinessException(ErrorCode.INQUIRY_NOT_FOUND));

        mockMvc.perform(get("/api/v1/admin/inquiries/999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));

        verify(adminInquiryService).getInquiry(999L);
    }

    @Test
    @DisplayName("문의 답변: inquiryId 7L, 관리자 ID 1L, 답변 본문이 서비스에 전달된다")
    void answer_passesParamsAndPrincipalCorrectly() throws Exception {
        String adminToken = createToken(1L, "admin@test.com", MemberRole.ADMIN);
        Inquiry answered = Inquiry.create(
                10L,
                "테스트 문의 제목",
                "테스트 문의 내용"
        );
        ReflectionTestUtils.setField(answered, "id", 7L);
        answered.answer(1L, "테스트 답변", LocalDateTime.of(2026, 10, 1, 12, 0));
        ReflectionTestUtils.setField(
                answered,
                "createdAt",
                LocalDateTime.of(2026, 10, 1, 12, 0)
        );
        given(adminInquiryService.answer(eq(7L), eq(1L), any(InquiryAnswerRequest.class)))
                .willReturn(answered);

        mockMvc.perform(post("/api/v1/admin/inquiries/7/answer")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"테스트 답변\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(7L))
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.answerContent").value("테스트 답변"));

        ArgumentCaptor<InquiryAnswerRequest> captor = ArgumentCaptor.forClass(InquiryAnswerRequest.class);
        verify(adminInquiryService).answer(eq(7L), eq(1L), captor.capture());
        assertThat(captor.getValue().content()).isEqualTo("테스트 답변");
    }
}
