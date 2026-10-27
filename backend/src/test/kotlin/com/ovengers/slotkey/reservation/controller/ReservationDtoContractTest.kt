package com.ovengers.slotkey.reservation.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
import com.ovengers.slotkey.reservation.service.AdminReservationService
import com.ovengers.slotkey.reservation.service.ReservationCancelService
import com.ovengers.slotkey.reservation.service.ReservationCheckOutService
import com.ovengers.slotkey.reservation.service.ReservationExtendService
import com.ovengers.slotkey.reservation.service.ReservationHoldService
import com.ovengers.slotkey.reservation.service.ReservationPaymentConfirmService
import com.ovengers.slotkey.reservation.service.ReservationQueryService
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.eqNonNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Page
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(ReservationController::class, AdminReservationController::class)
@ActiveProfiles("test")
@Import(
    SecurityConfig::class,
    ClockConfig::class,
    JwtProvider::class,
    WebMvcConfig::class,
    CurrentMemberArgumentResolver::class,
    GlobalExceptionHandler::class,
)
class ReservationDtoContractTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    @MockBean private lateinit var holdService: ReservationHoldService

    @MockBean private lateinit var paymentService: ReservationPaymentConfirmService

    @MockBean private lateinit var cancelService: ReservationCancelService

    @MockBean private lateinit var extendService: ReservationExtendService

    @MockBean private lateinit var checkOutService: ReservationCheckOutService

    @MockBean private lateinit var queryService: ReservationQueryService

    @MockBean private lateinit var adminService: AdminReservationService

    @MockBean private lateinit var authSessionService: AuthSessionService

    @MockBean private lateinit var inactiveMemberAccessLogService: InactiveMemberAccessLogService

    @BeforeEach
    fun authenticate() {
        given(authSessionService.validate("dto-session", 1L)).willReturn(AuthSessionService.ValidationResult.VALID)
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "create-request, spaceId, /api/v1/reservations", "create-request, date, /api/v1/reservations",
            "create-request, startTime, /api/v1/reservations", "create-request, endTime, /api/v1/reservations",
            "extend-request, expectedEndTime, /api/v1/reservations/10/extend",
            "extend-request, newEndTime, /api/v1/reservations/10/extend",
            "pay-request, spaceVersion, /api/v1/reservations/10/pay",
        ],
    )
    fun missingAndExplicitNullKeepValidationResponse(
        fixture: String,
        field: String,
        path: String,
    ) {
        for (explicitNull in booleanArrayOf(false, true)) {
            val body: ObjectNode = fixture(fixture) as ObjectNode
            if (explicitNull) body.putNull(field) else body.remove(field)
            mockMvc
                .perform(
                    post(path)
                        .header("Authorization", token(MemberRole.USER))
                        .header("Idempotency-Key", "dto-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString())
                        .header("Accept-Language", "en"),
                ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("\$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("\$.message").value("must not be null"))
                .andExpect(jsonPath("\$.data").doesNotExist())
        }
        verifyNoInteractions(holdService, paymentService, extendService)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}"])
    fun forceCancelReasonMissingNullOrBlankIsRejected(body: String) {
        mockMvc
            .perform(
                post("/api/v1/admin/reservations/10/force-cancel")
                    .header("Authorization", token(MemberRole.ADMIN))
                    .header("Accept-Language", "en")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("\$.message").value("must not be blank"))
        verifyNoInteractions(adminService)
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 501])
    fun forceCancelReasonLengthBoundary(length: Int) {
        if (length == 500) {
            given(adminService.forceCancel(eqNonNull(10L), anyString(), eqNonNull(1L)))
                .willThrow(BusinessException(ErrorCode.RESERVATION_NOT_FOUND))
        }
        mockMvc
            .perform(
                post("/api/v1/admin/reservations/10/force-cancel")
                    .header("Authorization", token(MemberRole.ADMIN))
                    .header("Accept-Language", "en")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"" + "a".repeat(length) + "\"}"),
            ).andExpect(status().`is`(if (length == 500) 404 else 400))
            .andExpect(jsonPath("\$.code").value(if (length == 500) "RESERVATION_NOT_FOUND" else "VALIDATION_FAILED"))
        if (length == 500) {
            verify(adminService).forceCancel(10L, "a".repeat(length), 1L)
        } else {
            verifyNoInteractions(adminService)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["{\"spaceVersion\":2147483648}", "{\"spaceVersion\":\"wrong\"}"])
    fun invalidPaymentNumberKeepsGenericUnreadableBodyError(body: String) {
        mockMvc
            .perform(
                post("/api/v1/reservations/10/pay")
                    .header("Authorization", token(MemberRole.USER))
                    .header("Idempotency-Key", "dto-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("\$.message").value("요청 데이터가 올바르지 않습니다."))
        verifyNoInteractions(paymentService)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "2147483647"])
    fun validPaymentVersionReachesExistingService(version: Int) {
        given(paymentService.confirm(1L, 10L, version, "dto-key"))
            .willThrow(BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER))
        mockMvc
            .perform(
                post("/api/v1/reservations/10/pay")
                    .header("Authorization", token(MemberRole.USER))
                    .header("Idempotency-Key", "dto-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"spaceVersion\":" + version + "}"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("\$.code").value("FORBIDDEN_NOT_OWNER"))
        verify(paymentService).confirm(1L, 10L, version, "dto-key")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "?status=&keyword=", "?keyword=%20%20%20"])
    fun omittedAndBlankMemberFiltersRemainValid(query: String) {
        given(queryService.getMyReservations(eqNonNull(1L), anyNonNull(), anyNonNull())).willReturn(Page.empty())
        mockMvc
            .perform(get("/api/v1/reservations" + query).header("Authorization", token(MemberRole.USER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("\$.data.content").isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "?date=&spaceId=&status=", "?date=2026-10-23&spaceId=20&status=CONFIRMED"])
    fun omittedEmptyAndValidAdminFiltersRemainValid(query: String) {
        given(adminService.searchReservations(anyNonNull(), anyNonNull())).willReturn(Page.empty())
        mockMvc
            .perform(get("/api/v1/admin/reservations" + query).header("Authorization", token(MemberRole.ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("\$.data.content").isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["?date=invalid", "?status=INVALID", "?spaceId=invalid", "?spaceId=0", "?spaceId=-1"])
    fun malformedAdminFiltersKeepExistingError(query: String) {
        mockMvc
            .perform(get("/api/v1/admin/reservations" + query).header("Authorization", token(MemberRole.ADMIN)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("VALIDATION_FAILED"))
        verifyNoInteractions(adminService)
    }

    @Test
    fun userCannotForceCancelOthersReservation() {
        mockMvc
            .perform(
                post("/api/v1/admin/reservations/10/force-cancel")
                    .header("Authorization", token(MemberRole.USER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"관리자 사유\"}"),
            ).andExpect(status().isForbidden())
        verifyNoInteractions(adminService)
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "spaceId, 9223372036854775808, unreadable", "date, invalid-date, unreadable",
            "startTime, invalid-time, unreadable", "spaceId, EMPTY, required",
            "date, EMPTY, required", "startTime, EMPTY, required",
        ],
    )
    fun malformedAndEmptyCreateFieldsKeepExistingErrorCategory(
        field: String,
        value: String,
        category: String,
    ) {
        val body: ObjectNode = fixture("create-request") as ObjectNode
        if ("9223372036854775808".equals(value)) {
            body.set<JsonNode>(field, objectMapper.readTree(value))
        } else {
            body.put(field, if ("EMPTY".equals(value)) "" else value)
        }
        mockMvc
            .perform(
                post("/api/v1/reservations")
                    .header("Authorization", token(MemberRole.USER))
                    .header("Accept-Language", "en")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString()),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("\$.message").value(if ("required".equals(category)) "must not be null" else "요청 데이터가 올바르지 않습니다."))
        verifyNoInteractions(holdService)
    }

    @Test
    fun allMissingCreateFieldsKeepFourRequiredErrors() {
        mockMvc
            .perform(
                post("/api/v1/reservations")
                    .header("Authorization", token(MemberRole.USER))
                    .header("Accept-Language", "en")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("VALIDATION_FAILED"))
            .andExpect(
                jsonPath("\$.message").value(
                    "must not be null, must not be null, must not be null, must not be null",
                ),
            )
        verifyNoInteractions(holdService)
    }

    private fun token(role: MemberRole): String =
        "Bearer " +
            JwtUtil.createToken(
                secretKey,
                300_000L,
                mapOf("id" to 1L, "email" to "dto@slotkey.test", "role" to role.name, "sid" to "dto-session"),
            )

    private fun fixture(name: String): JsonNode {
        javaClass.getResourceAsStream("/golden/reservation/" + name + ".json").use { input ->
            assertThat(input).isNotNull()
            return objectMapper.readTree(input)
        }
    }
}
