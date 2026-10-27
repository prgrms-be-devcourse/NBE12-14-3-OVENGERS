package com.ovengers.slotkey.credit.controller

import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse
import com.ovengers.slotkey.credit.dto.response.ChargeOrderResponse
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.service.ChargeOrderConfirmService
import com.ovengers.slotkey.credit.service.ChargeOrderService
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
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.eqNonNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(ChargeOrderController::class)
@ActiveProfiles("test")
@Import(
    SecurityConfig::class,
    ClockConfig::class,
    JwtProvider::class,
    WebMvcConfig::class,
    CurrentMemberArgumentResolver::class,
    GlobalExceptionHandler::class,
)
class ChargeOrderControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var chargeOrderService: ChargeOrderService

    @MockBean
    private lateinit var chargeOrderConfirmService: ChargeOrderConfirmService

    @MockBean
    private lateinit var authSessionService: AuthSessionService

    @MockBean
    private lateinit var inactiveMemberAccessLogService: InactiveMemberAccessLogService

    @BeforeEach
    fun allowTestSession() {
        given(authSessionService.validate(anyString(), anyLong()))
            .willReturn(AuthSessionService.ValidationResult.VALID)
    }

    @Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    @Test
    @DisplayName("POST /api/v1/credits/charge-orders는 인증 회원의 PENDING 충전 주문을 생성하고 201을 반환한다")
    fun create_returnsCreatedPendingOrder() {
        val token =
            JwtUtil.createToken(
                secretKey,
                300_000L,
                mapOf(
                    "id" to 7L,
                    "email" to "member@test.com",
                    "role" to MemberRole.USER.name,
                    "sid" to "test-session",
                ),
            )
        val response =
            ChargeOrderResponse(
                "chg_550e8400-e29b-41d4-a716-446655440000",
                1_000L,
                ChargeOrderStatus.PENDING,
            )
        given(chargeOrderService.create(7L, 1_000L)).willReturn(response)

        mockMvc
            .perform(
                post("/api/v1/credits/charge-orders")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":1000}"),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("\$.status").value("SUCCESS"))
            .andExpect(
                jsonPath("\$.data.orderId")
                    .value("chg_550e8400-e29b-41d4-a716-446655440000"),
            ).andExpect(jsonPath("\$.data.amount").value(1_000L))
            .andExpect(jsonPath("\$.data.status").value("PENDING"))

        verify(chargeOrderService).create(7L, 1_000L)
    }

    @Test
    @DisplayName("인증 없이 충전 주문을 생성하면 401을 반환한다")
    fun create_withoutAuthentication_returnsUnauthorized() {
        mockMvc
            .perform(
                post("/api/v1/credits/charge-orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":1000}"),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("\$.code").value("AUTHENTICATION_REQUIRED"))

        verifyNoInteractions(chargeOrderService)
    }

    @Test
    @DisplayName("GET /api/v1/credits/charge-orders/{orderId}는 본인 주문 상태를 반환한다")
    fun get_ownedOrder_returnsOk() {
        val orderId = "chg_550e8400-e29b-41d4-a716-446655440000"
        given(chargeOrderService.get(7L, orderId)).willReturn(
            ChargeOrderResponse(
                orderId,
                1_000L,
                ChargeOrderStatus.CONFIRMING,
            ),
        )

        mockMvc
            .perform(
                get("/api/v1/credits/charge-orders/{orderId}", orderId)
                    .header("Authorization", "Bearer " + accessToken()),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("\$.data.orderId").value(orderId))
            .andExpect(jsonPath("\$.data.amount").value(1_000L))
            .andExpect(jsonPath("\$.data.status").value("CONFIRMING"))

        verify(chargeOrderService).get(7L, orderId)
    }

    @Test
    @DisplayName("충전 금액이 null이면 400을 반환한다")
    fun create_nullAmount_returnsBadRequest() {
        mockMvc
            .perform(authenticatedPost("{\"amount\":null}"))
            .andExpect(status().isBadRequest())

        verifyNoInteractions(chargeOrderService)
    }

    @Test
    @DisplayName("충전 금액이 숫자가 아니면 400을 반환한다")
    fun create_nonNumericAmount_returnsBadRequest() {
        mockMvc
            .perform(authenticatedPost("{\"amount\":\"abc\"}"))
            .andExpect(status().isBadRequest())

        verifyNoInteractions(chargeOrderService)
    }

    @Test
    @DisplayName("충전 금액이 long 범위를 초과하면 400을 반환한다")
    fun create_amountExceedsLongRange_returnsBadRequest() {
        mockMvc
            .perform(authenticatedPost("{\"amount\":9223372036854775808}"))
            .andExpect(status().isBadRequest())

        verifyNoInteractions(chargeOrderService)
    }

    @Test
    @DisplayName("승인 완료는 200 PAID를 반환한다")
    fun confirm_paid_returnsOk() {
        given(
            chargeOrderConfirmService.confirm(
                7L,
                "payment-key",
                "chg_550e8400-e29b-41d4-a716-446655440000",
                1_000L,
                "request-key",
            ),
        ).willReturn(
            ChargeOrderConfirmResponse(
                "chg_550e8400-e29b-41d4-a716-446655440000",
                1_000L,
                ChargeOrderStatus.PAID,
            ),
        )

        mockMvc
            .perform(authenticatedConfirm("request-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("\$.data.status").value("PAID"))
    }

    @Test
    @DisplayName("결과를 알 수 없는 승인 요청은 202 CONFIRMING을 반환한다")
    fun confirm_confirming_returnsAccepted() {
        given(
            chargeOrderConfirmService.confirm(
                7L,
                "payment-key",
                "chg_550e8400-e29b-41d4-a716-446655440000",
                1_000L,
                "request-key",
            ),
        ).willReturn(
            ChargeOrderConfirmResponse(
                "chg_550e8400-e29b-41d4-a716-446655440000",
                1_000L,
                ChargeOrderStatus.CONFIRMING,
            ),
        )

        mockMvc
            .perform(authenticatedConfirm("request-key"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("\$.data.status").value("CONFIRMING"))
    }

    @Test
    @DisplayName("승인 요청의 Idempotency-Key 헤더가 없으면 400을 반환한다")
    fun confirm_withoutIdempotencyKey_returnsBadRequest() {
        willThrow(BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED))
            .given(chargeOrderConfirmService)
            .confirm(
                7L,
                "payment-key",
                "chg_550e8400-e29b-41d4-a716-446655440000",
                1_000L,
                null,
            )

        mockMvc
            .perform(authenticatedConfirm(null))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
    }

    @Test
    @DisplayName("승인 요청의 paymentKey가 공백이면 400을 반환한다")
    fun confirm_blankPaymentKey_returnsBadRequest() {
        mockMvc
            .perform(
                authenticatedConfirm(
                    "request-key",
                    """
                    {
                      "paymentKey": "   ",
                      "orderId": "chg_550e8400-e29b-41d4-a716-446655440000",
                      "amount": 1000
                    }
                    """.trimIndent(),
                ),
            ).andExpect(status().isBadRequest())

        verifyNoInteractions(chargeOrderConfirmService)
    }

    @Test
    @DisplayName("승인 요청의 orderId가 공백이면 400을 반환한다")
    fun confirm_blankOrderId_returnsBadRequest() {
        mockMvc
            .perform(
                authenticatedConfirm(
                    "request-key",
                    """
                    {
                      "paymentKey": "payment-key",
                      "orderId": "   ",
                      "amount": 1000
                    }
                    """.trimIndent(),
                ),
            ).andExpect(status().isBadRequest())

        verifyNoInteractions(chargeOrderConfirmService)
    }

    @Test
    @DisplayName("승인 요청의 amount가 null이면 400을 반환한다")
    fun confirm_nullAmount_returnsBadRequest() {
        mockMvc
            .perform(
                authenticatedConfirm(
                    "request-key",
                    """
                    {
                      "paymentKey": "payment-key",
                      "orderId": "chg_550e8400-e29b-41d4-a716-446655440000",
                      "amount": null
                    }
                    """.trimIndent(),
                ),
            ).andExpect(status().isBadRequest())

        verifyNoInteractions(chargeOrderConfirmService)
    }

    private fun authenticatedPost(content: String): MockHttpServletRequestBuilder =
        post("/api/v1/credits/charge-orders")
            .header("Authorization", "Bearer " + accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(content)

    @Test
    @DisplayName("GET /api/v1/credits/charge-orders는 본인 충전 내역을 페이지로 반환한다")
    fun list_returnsMyHistoryPage() {
        given(chargeOrderService.getMyHistory(eqNonNull(7L), anyNonNull()))
            .willReturn(
                PageImpl(
                    listOf(
                        com.ovengers.slotkey.credit.dto.response.ChargeOrderHistoryResponse(
                            "chg_1",
                            5_000L,
                            ChargeOrderStatus.PAID,
                            "간편결제",
                            "토스페이",
                            LocalDateTime.of(2026, 10, 13, 9, 0),
                            LocalDateTime.of(2026, 10, 13, 9, 1),
                        ),
                    ),
                    PageRequest.of(0, 10),
                    1,
                ),
            )

        mockMvc
            .perform(
                get("/api/v1/credits/charge-orders")
                    .header("Authorization", "Bearer " + accessToken()),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("\$.data.content[0].orderId").value("chg_1"))
            .andExpect(jsonPath("\$.data.content[0].amount").value(5_000L))
            .andExpect(jsonPath("\$.data.content[0].status").value("PAID"))
            .andExpect(jsonPath("\$.data.content[0].paymentMethod").value("간편결제"))
            .andExpect(jsonPath("\$.data.content[0].paymentProvider").value("토스페이"))
            .andExpect(jsonPath("\$.data.totalElements").value(1))
    }

    @Test
    @DisplayName("인증 없이 충전 내역을 조회하면 401을 반환한다")
    fun list_withoutAuthentication_returnsUnauthorized() {
        mockMvc
            .perform(get("/api/v1/credits/charge-orders"))
            .andExpect(status().isUnauthorized())

        verifyNoInteractions(chargeOrderService)
    }

    private fun accessToken(): String =
        JwtUtil.createToken(
            secretKey,
            300_000L,
            mapOf(
                "id" to 7L,
                "email" to "member@test.com",
                "role" to MemberRole.USER.name,
                "sid" to "test-session",
            ),
        )

    private fun authenticatedConfirm(idempotencyKey: String?): MockHttpServletRequestBuilder =
        authenticatedConfirm(
            idempotencyKey,
            """
            {
              "paymentKey": "payment-key",
              "orderId": "chg_550e8400-e29b-41d4-a716-446655440000",
              "amount": 1000
            }
            """.trimIndent(),
        )

    private fun authenticatedConfirm(
        idempotencyKey: String?,
        content: String,
    ): MockHttpServletRequestBuilder {
        val token =
            JwtUtil.createToken(
                secretKey,
                300_000L,
                mapOf(
                    "id" to 7L,
                    "email" to "member@test.com",
                    "role" to MemberRole.USER.name,
                    "sid" to "test-session",
                ),
            )
        val request =
            post(
                "/api/v1/credits/charge-orders/confirm",
            ).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(content)
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey)
        }
        return request
    }
}
