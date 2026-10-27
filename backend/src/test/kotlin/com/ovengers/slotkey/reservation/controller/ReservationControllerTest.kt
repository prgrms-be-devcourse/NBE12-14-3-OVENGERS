package com.ovengers.slotkey.reservation.controller

import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.config.SecurityConfig
import com.ovengers.slotkey.global.config.WebMvcConfig
import com.ovengers.slotkey.global.error.GlobalExceptionHandler
import com.ovengers.slotkey.global.security.CurrentMemberArgumentResolver
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.global.security.jwt.JwtUtil
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.reservation.service.ReservationCancelService
import com.ovengers.slotkey.reservation.service.ReservationCheckOutService
import com.ovengers.slotkey.reservation.service.ReservationExtendService
import com.ovengers.slotkey.reservation.service.ReservationHoldService
import com.ovengers.slotkey.reservation.service.ReservationPaymentConfirmService
import com.ovengers.slotkey.reservation.service.ReservationQueryService
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(ReservationController::class)
@ActiveProfiles("test")
@Import(
    SecurityConfig::class,
    ClockConfig::class,
    JwtProvider::class,
    WebMvcConfig::class,
    CurrentMemberArgumentResolver::class,
    GlobalExceptionHandler::class,
)
class ReservationControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    @MockBean
    private lateinit var reservationHoldService: ReservationHoldService

    @MockBean
    private lateinit var reservationPaymentConfirmService: ReservationPaymentConfirmService

    @MockBean
    private lateinit var reservationCancelService: ReservationCancelService

    @MockBean
    private lateinit var reservationExtendService: ReservationExtendService

    @MockBean
    private lateinit var reservationCheckOutService: ReservationCheckOutService

    @MockBean
    private lateinit var reservationQueryService: ReservationQueryService

    @MockBean
    private lateinit var authSessionService: AuthSessionService

    @MockBean
    private lateinit var inactiveMemberAccessLogService: InactiveMemberAccessLogService

    @Test
    @DisplayName("예약 결제 시 멱등키 헤더가 없으면 400과 전용 오류 코드를 반환하고 결제를 수행하지 않는다")
    fun shouldRejectPaymentWhenIdempotencyKeyIsMissing() {
        given(
            authSessionService.validate(
                "test-session",
                1L,
            ),
        ).willReturn(AuthSessionService.ValidationResult.VALID)

        val accessToken: String =
            JwtUtil.createToken(
                secretKey,
                300_000L,
                mapOf(
                    "id" to 1L,
                    "email" to "member@test.com",
                    "role" to MemberRole.USER.name,
                    "sid" to "test-session",
                ),
            )

        mockMvc
            .perform(
                post("/api/v1/reservations/{reservationId}/pay", 1L)
                    .header(
                        "Authorization",
                        "Bearer " + accessToken,
                    ).contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "spaceVersion": 0
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.status").value("FAIL"))
            .andExpect(
                jsonPath("\$.code").value(
                    "IDEMPOTENCY_KEY_REQUIRED",
                ),
            ).andExpect(
                jsonPath("\$.message").value(
                    "Idempotency-Key 헤더가 필요합니다.",
                ),
            )

        // 인증 검증을 거친 요청인지 확인
        verify(authSessionService).validate(
            "test-session",
            1L,
        )

        // 헤더 누락 시 결제 서비스가 호출되지 않는지 확인
        verifyNoInteractions(
            reservationPaymentConfirmService,
        )
    }
}
