package com.ovengers.slotkey.global.security

import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.config.SecurityConfig
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.global.security.jwt.JwtUtil
import com.ovengers.slotkey.member.entity.MemberRole
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ErrorDispatchSecurityIntegrationTest.TestAppConfig::class],
)
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "custom.jwt.secret-key=slotkey-test-only-secret-key-0123456789-abcdefghijklmnopqrstuvwxyz",
        "custom.jwt.expire-millis=300000",
        "server.error.include-message=never",
        "server.error.include-binding-errors=never",
        "server.error.include-stacktrace=never",
        "server.error.include-exception=false",
    ],
)
@DisplayName("실제 서블릿 컨테이너 ERROR 디스패치 보안 통합 테스트")
class ErrorDispatchSecurityIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    @BeforeEach
    fun setUp() {
        DispatchRecordingFilter.records.clear()
    }

    @Test
    @DisplayName("ADMIN 정상 요청은 200 OK를 반환한다")
    fun adminOk_returns200() {
        val adminToken = createToken(id = 1L, email = "admin@test.com", role = MemberRole.ADMIN)
        val request = HttpEntity<Void>(createAuthHeaders(adminToken))

        val response =
            restTemplate.exchange(
                "/api/v1/admin/test-error-dispatch/ok",
                HttpMethod.GET,
                request,
                String::class.java,
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"message\":\"ok\"")
    }

    @Test
    @DisplayName("ADMIN 요청 중 미처리 예외 발생 시 컨테이너 ERROR 디스패치를 거쳐 500을 반환하며 401로 변환되지 않는다")
    fun adminThrow_triggersErrorDispatchAndReturns500() {
        val adminToken = createToken(id = 1L, email = "admin@test.com", role = MemberRole.ADMIN)
        val request = HttpEntity<Void>(createAuthHeaders(adminToken))

        val response =
            restTemplate.exchange(
                "/api/v1/admin/test-error-dispatch/throw",
                HttpMethod.GET,
                request,
                String::class.java,
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)

        val body = response.body
        assertThat(body).isNotNull()
        assertThat(body).doesNotContain("AUTHENTICATION_REQUIRED")
        assertThat(body).doesNotContain("test-dispatch-error-identifier")
        assertThat(body).doesNotContain("Exception")
        assertThat(body).doesNotContain("at com.ovengers")
        assertThat(body).doesNotContain(adminToken)

        // 컨테이너 ERROR 디스패치가 관찰 필터에 기록되었는지 확인
        val errorDispatched = DispatchRecordingFilter.records.any { it.dispatcherType == DispatcherType.ERROR }
        assertThat(errorDispatched).isTrue()
    }

    @Test
    @DisplayName("익명 요청 /ok는 401을 반환한다")
    fun anonymousOk_returns401() {
        val response =
            restTemplate.getForEntity(
                "/api/v1/admin/test-error-dispatch/ok",
                String::class.java,
            )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body).contains("AUTHENTICATION_REQUIRED")
    }

    @Test
    @DisplayName("익명 요청 /throw는 401을 반환하고 내부 예외가 실행되지 않는다")
    fun anonymousThrow_returns401() {
        val response =
            restTemplate.getForEntity(
                "/api/v1/admin/test-error-dispatch/throw",
                String::class.java,
            )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body).contains("AUTHENTICATION_REQUIRED")
    }

    @Test
    @DisplayName("USER 권한 요청 /ok는 403 ACCESS_DENIED를 반환한다")
    fun userRoleOk_returns403() {
        val userToken = createToken(id = 2L, email = "user@test.com", role = MemberRole.USER)
        val request = HttpEntity<Void>(createAuthHeaders(userToken))

        val response =
            restTemplate.exchange(
                "/api/v1/admin/test-error-dispatch/ok",
                HttpMethod.GET,
                request,
                String::class.java,
            )
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body).contains("ACCESS_DENIED")
    }

    @Test
    @DisplayName("USER 권한 요청 /throw는 403 ACCESS_DENIED를 반환한다")
    fun userRoleThrow_returns403() {
        val userToken = createToken(id = 2L, email = "user@test.com", role = MemberRole.USER)
        val request = HttpEntity<Void>(createAuthHeaders(userToken))

        val response =
            restTemplate.exchange(
                "/api/v1/admin/test-error-dispatch/throw",
                HttpMethod.GET,
                request,
                String::class.java,
            )
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body).contains("ACCESS_DENIED")
    }

    @Test
    @DisplayName("잘못된 토큰 요청은 401 INVALID_ACCESS_TOKEN을 반환한다")
    fun invalidToken_returns401() {
        val headers = HttpHeaders().apply { set(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token") }
        val request = HttpEntity<Void>(headers)

        val response =
            restTemplate.exchange(
                "/api/v1/admin/test-error-dispatch/ok",
                HttpMethod.GET,
                request,
                String::class.java,
            )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body).contains("INVALID_ACCESS_TOKEN")
    }

    @Test
    @DisplayName("익명의 일반 REQUEST /error 직접 요청은 401 AUTHENTICATION_REQUIRED를 반환한다")
    fun anonymousDirectGetError_returns401() {
        val response =
            restTemplate.getForEntity(
                "/error",
                String::class.java,
            )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body).contains("AUTHENTICATION_REQUIRED")
    }

    private fun createToken(
        id: Long,
        email: String,
        role: MemberRole,
    ): String =
        JwtUtil.createToken(
            secretKey,
            300_000L,
            mapOf(
                "id" to id,
                "email" to email,
                "role" to role.name,
                "sid" to "test-session",
            ),
        )

    private fun createAuthHeaders(token: String): HttpHeaders = HttpHeaders().apply { setBearerAuth(token) }

    @SpringBootConfiguration
    @EnableAutoConfiguration(
        exclude = [
            DataSourceAutoConfiguration::class,
            HibernateJpaAutoConfiguration::class,
            FlywayAutoConfiguration::class,
            JpaRepositoriesAutoConfiguration::class,
        ],
    )
    @EnableWebSecurity
    @Import(SecurityConfig::class, ClockConfig::class, JwtProvider::class)
    class TestAppConfig {
        @Bean
        fun testErrorDispatchController(): TestErrorDispatchController = TestErrorDispatchController()

        @Bean
        fun dispatchRecordingFilter(): DispatchRecordingFilter = DispatchRecordingFilter()

        @Bean
        fun authSessionService(): AuthSessionService {
            val service = mock(AuthSessionService::class.java)
            given(service.validate(anyString(), anyLong()))
                .willReturn(AuthSessionService.ValidationResult.VALID)
            return service
        }

        @Bean
        fun inactiveMemberAccessLogService(): InactiveMemberAccessLogService = mock(InactiveMemberAccessLogService::class.java)
    }

    @RestController
    @RequestMapping("/api/v1/admin/test-error-dispatch")
    class TestErrorDispatchController {
        @GetMapping("/ok")
        fun ok(): ResponseEntity<Map<String, String>> = ResponseEntity.ok(mapOf("message" to "ok"))

        @GetMapping("/throw")
        fun throwException(): ResponseEntity<Void> = throw IllegalArgumentException("test-dispatch-error-identifier")
    }

    class DispatchRecordingFilter : OncePerRequestFilter() {
        data class RecordedDispatch(
            val dispatcherType: DispatcherType,
            val requestUri: String,
            val status: Int,
        )

        override fun shouldNotFilterAsyncDispatch(): Boolean = false

        override fun shouldNotFilterErrorDispatch(): Boolean = false

        @Throws(ServletException::class, IOException::class)
        override fun doFilterInternal(
            request: HttpServletRequest,
            response: HttpServletResponse,
            filterChain: FilterChain,
        ) {
            try {
                filterChain.doFilter(request, response)
            } finally {
                records.add(
                    RecordedDispatch(
                        dispatcherType = request.dispatcherType,
                        requestUri = request.requestURI,
                        status = response.status,
                    ),
                )
            }
        }

        companion object {
            val records: MutableList<RecordedDispatch> = CopyOnWriteArrayList()
        }
    }
}
