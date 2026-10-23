package com.ovengers.slotkey.reservation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService;
import com.ovengers.slotkey.auth.service.AuthSessionService;
import com.ovengers.slotkey.global.config.ClockConfig;
import com.ovengers.slotkey.global.config.SecurityConfig;
import com.ovengers.slotkey.global.config.WebMvcConfig;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.global.error.GlobalExceptionHandler;
import com.ovengers.slotkey.global.security.CurrentMemberArgumentResolver;
import com.ovengers.slotkey.global.security.jwt.JwtProvider;
import com.ovengers.slotkey.global.security.jwt.JwtUtil;
import com.ovengers.slotkey.member.entity.MemberRole;
import com.ovengers.slotkey.reservation.dto.request.*;
import com.ovengers.slotkey.reservation.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ReservationController.class, AdminReservationController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, ClockConfig.class, JwtProvider.class, WebMvcConfig.class,
        CurrentMemberArgumentResolver.class, GlobalExceptionHandler.class})
class ReservationDtoContractTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Value("${custom.jwt.secret-key}") private String secretKey;
    @MockBean private ReservationHoldService holdService;
    @MockBean private ReservationPaymentConfirmService paymentService;
    @MockBean private ReservationCancelService cancelService;
    @MockBean private ReservationExtendService extendService;
    @MockBean private ReservationCheckOutService checkOutService;
    @MockBean private ReservationQueryService queryService;
    @MockBean private AdminReservationService adminService;
    @MockBean private AuthSessionService authSessionService;
    @MockBean private InactiveMemberAccessLogService inactiveMemberAccessLogService;

    @BeforeEach
    void authenticate() {
        given(authSessionService.validate("dto-session", 1L)).willReturn(AuthSessionService.ValidationResult.VALID);
    }

    @ParameterizedTest
    @CsvSource({
            "create-request, spaceId, /api/v1/reservations", "create-request, date, /api/v1/reservations",
            "create-request, startTime, /api/v1/reservations", "create-request, endTime, /api/v1/reservations",
            "extend-request, expectedEndTime, /api/v1/reservations/10/extend",
            "extend-request, newEndTime, /api/v1/reservations/10/extend",
            "pay-request, spaceVersion, /api/v1/reservations/10/pay"
    })
    void missingAndExplicitNullKeepValidationResponse(String fixture, String field, String path) throws Exception {
        for (boolean explicitNull : new boolean[]{false, true}) {
            ObjectNode body = (ObjectNode) fixture(fixture);
            if (explicitNull) body.putNull(field); else body.remove(field);
            mockMvc.perform(post(path).header("Authorization", token(MemberRole.USER))
                            .header("Idempotency-Key", "dto-key").contentType(MediaType.APPLICATION_JSON)
                            .content(body.toString()).header("Accept-Language", "en"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("must not be null"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        verifyNoInteractions(holdService, paymentService, extendService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}"})
    void forceCancelReasonMissingNullOrBlankIsRejected(String body) throws Exception {
        mockMvc.perform(post("/api/v1/admin/reservations/10/force-cancel")
                        .header("Authorization", token(MemberRole.ADMIN)).header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("must not be blank"));
        verifyNoInteractions(adminService);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 501})
    void forceCancelReasonLengthBoundary(int length) throws Exception {
        if (length == 500) {
            given(adminService.forceCancel(eq(10L), anyString(), eq(1L)))
                    .willThrow(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        }
        mockMvc.perform(post("/api/v1/admin/reservations/10/force-cancel")
                        .header("Authorization", token(MemberRole.ADMIN)).header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + "a".repeat(length) + "\"}"))
                .andExpect(status().is(length == 500 ? 404 : 400))
                .andExpect(jsonPath("$.code").value(length == 500 ? "RESERVATION_NOT_FOUND" : "VALIDATION_FAILED"));
        if (length == 500) verify(adminService).forceCancel(10L, "a".repeat(length), 1L);
        else verifyNoInteractions(adminService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"spaceVersion\":2147483648}", "{\"spaceVersion\":\"wrong\"}"})
    void invalidPaymentNumberKeepsGenericUnreadableBodyError(String body) throws Exception {
        mockMvc.perform(post("/api/v1/reservations/10/pay").header("Authorization", token(MemberRole.USER))
                        .header("Idempotency-Key", "dto-key").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청 데이터가 올바르지 않습니다."));
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2147483647"})
    void validPaymentVersionReachesExistingService(int version) throws Exception {
        given(paymentService.confirm(1L, 10L, version, "dto-key"))
                .willThrow(new BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER));
        mockMvc.perform(post("/api/v1/reservations/10/pay").header("Authorization", token(MemberRole.USER))
                        .header("Idempotency-Key", "dto-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceVersion\":" + version + "}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_NOT_OWNER"));
        verify(paymentService).confirm(1L, 10L, version, "dto-key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?status=&keyword=", "?keyword=%20%20%20"})
    void omittedAndBlankMemberFiltersRemainValid(String query) throws Exception {
        given(queryService.getMyReservations(eq(1L), any(), any())).willReturn(Page.empty());
        mockMvc.perform(get("/api/v1/reservations" + query).header("Authorization", token(MemberRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?date=&spaceId=&status=", "?date=2026-10-23&spaceId=20&status=CONFIRMED"})
    void omittedEmptyAndValidAdminFiltersRemainValid(String query) throws Exception {
        given(adminService.searchReservations(any(), any())).willReturn(Page.empty());
        mockMvc.perform(get("/api/v1/admin/reservations" + query).header("Authorization", token(MemberRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?date=invalid", "?status=INVALID", "?spaceId=invalid", "?spaceId=0", "?spaceId=-1"})
    void malformedAdminFiltersKeepExistingError(String query) throws Exception {
        mockMvc.perform(get("/api/v1/admin/reservations" + query).header("Authorization", token(MemberRole.ADMIN)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(adminService);
    }

    @Test
    void userCannotForceCancelOthersReservation() throws Exception {
        mockMvc.perform(post("/api/v1/admin/reservations/10/force-cancel")
                        .header("Authorization", token(MemberRole.USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"관리자 사유\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adminService);
    }

    @ParameterizedTest
    @CsvSource({"spaceId, 9223372036854775808, unreadable", "date, invalid-date, unreadable",
            "startTime, invalid-time, unreadable", "spaceId, EMPTY, required",
            "date, EMPTY, required", "startTime, EMPTY, required"})
    void malformedAndEmptyCreateFieldsKeepExistingErrorCategory(String field, String value, String category) throws Exception {
        ObjectNode body = (ObjectNode) fixture("create-request");
        if ("9223372036854775808".equals(value)) body.set(field, objectMapper.readTree(value));
        else body.put(field, "EMPTY".equals(value) ? "" : value);
        mockMvc.perform(post("/api/v1/reservations").header("Authorization", token(MemberRole.USER))
                        .header("Accept-Language", "en").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("required".equals(category)
                        ? "must not be null" : "요청 데이터가 올바르지 않습니다."));
        verifyNoInteractions(holdService);
    }

    @Test
    void allMissingCreateFieldsKeepFourRequiredErrors() throws Exception {
        mockMvc.perform(post("/api/v1/reservations").header("Authorization", token(MemberRole.USER))
                        .header("Accept-Language", "en").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        "must not be null, must not be null, must not be null, must not be null"));
        verifyNoInteractions(holdService);
    }

    private String token(MemberRole role) {
        return "Bearer " + JwtUtil.createToken(secretKey, 300_000L,
                Map.of("id", 1L, "email", "dto@slotkey.test", "role", role.name(), "sid", "dto-session"));
    }

    private JsonNode fixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/golden/reservation/" + name + ".json")) {
            assertThat(input).isNotNull();
            return objectMapper.readTree(input);
        }
    }
}
