package com.ovengers.slotkey.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.credit.controller.ChargeOrderController
import com.ovengers.slotkey.credit.service.ChargeOrderConfirmService
import com.ovengers.slotkey.credit.service.ChargeOrderService
import com.ovengers.slotkey.credit.webhook.TossWebhookController
import com.ovengers.slotkey.credit.webhook.TossWebhookReceiver
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.config.SecurityConfig
import com.ovengers.slotkey.global.config.WebMvcConfig
import com.ovengers.slotkey.global.error.GlobalExceptionHandler
import com.ovengers.slotkey.global.security.CurrentMemberArgumentResolver
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.global.security.jwt.JwtUtil
import com.ovengers.slotkey.notification.controller.NotificationController
import com.ovengers.slotkey.notification.service.NotificationQueryService
import com.ovengers.slotkey.waitlist.controller.WaitlistController
import com.ovengers.slotkey.waitlist.service.WaitlistService
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc

@WebMvcTest(
    ChargeOrderController::class,
    WaitlistController::class,
    NotificationController::class,
    TossWebhookController::class,
)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import(
    SecurityConfig::class,
    ClockConfig::class,
    JwtProvider::class,
    WebMvcConfig::class,
    CurrentMemberArgumentResolver::class,
    GlobalExceptionHandler::class,
)
abstract class HttpBodyGoldenSupport {
    @Autowired
    protected lateinit var mvc: MockMvc

    @Autowired
    protected lateinit var mapper: ObjectMapper

    @MockBean
    protected lateinit var chargeOrders: ChargeOrderService

    @MockBean
    protected lateinit var confirmations: ChargeOrderConfirmService

    @MockBean
    protected lateinit var waitlist: WaitlistService

    @MockBean
    protected lateinit var notifications: NotificationQueryService

    @MockBean
    protected lateinit var receiver: TossWebhookReceiver

    @MockBean
    private lateinit var sessions: AuthSessionService

    @MockBean
    private lateinit var inactiveAccess: InactiveMemberAccessLogService

    @Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    @BeforeEach
    fun allowSession() {
        given(sessions.validate(anyString(), anyLong())).willReturn(AuthSessionService.ValidationResult.VALID)
    }

    protected fun authorization(): String =
        "Bearer " +
            JwtUtil.createToken(
                secretKey,
                300_000L,
                mapOf("id" to 7L, "email" to "user@slotkey.test", "role" to "USER", "sid" to "test-session"),
            )
}
