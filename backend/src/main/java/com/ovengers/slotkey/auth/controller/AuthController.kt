package com.ovengers.slotkey.auth.controller

import com.ovengers.slotkey.auth.dto.request.LoginRequest
import com.ovengers.slotkey.auth.dto.request.PasswordResetRequest
import com.ovengers.slotkey.auth.dto.request.SignupRequest
import com.ovengers.slotkey.auth.dto.response.LoginResponse
import com.ovengers.slotkey.auth.dto.response.SignupResponse
import com.ovengers.slotkey.auth.ratelimit.LoginRateLimitService
import com.ovengers.slotkey.auth.service.AuthService
import com.ovengers.slotkey.auth.service.PasswordResetService
import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.member.dto.request.MemberWithdrawalRequest
import com.ovengers.slotkey.member.service.MemberService
import com.ovengers.slotkey.member.service.MemberWithdrawalService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.max
import io.swagger.v3.oas.annotations.responses.ApiResponse as OpenApiResponse

@Tag(name = "인증", description = "회원가입, 로그인, 토큰 재발급 및 로그아웃 API")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val memberService: MemberService,
    private val authService: AuthService,
    private val memberWithdrawalService: MemberWithdrawalService,
    private val passwordResetService: PasswordResetService,
    private val loginRateLimitService: LoginRateLimitService,
    private val clock: Clock,
) {
    @Value("\${app.auth.cookie.secure}")
    private var cookieSecure: Boolean = false

    @Value("\${app.auth.cookie.same-site}")
    private lateinit var cookieSameSite: String

    @Operation(
        summary = "회원가입",
        description = """
            이메일 인증 완료 후 발급받은 가입용 증표와
            이메일, 비밀번호, 비밀번호 확인, 닉네임으로 가입합니다.
            증표는 해당 이메일의 가입용이어야 하며 유효 시간 내에 한 번만 사용할 수 있습니다.
            가입 성공 시 회원 정보를 반환합니다. 로그인은 별도로 진행해야 합니다.
        """,
    )
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "201", description = "회원가입 성공", useReturnTypeSchema = true),
            OpenApiResponse(
                responseCode = "400",
                description = "필수 입력값 누락, 입력 형식 오류 또는 비밀번호 확인 불일치",
                content = [
                    Content(
                        mediaType = "application/json",
                        examples = [
                            ExampleObject(
                                name = "입력값 검증 실패",
                                value = """{"status":"FAIL","code":"VALIDATION_FAILED","message":"비밀번호 확인을 입력해주세요."}""",
                            ),
                            ExampleObject(
                                name = "비밀번호 확인 불일치",
                                value = """{"status":"FAIL","code":"PASSWORD_CONFIRM_MISMATCH","message":"비밀번호가 일치하지 않습니다."}""",
                            ),
                        ],
                    ),
                ],
            ),
            OpenApiResponse(
                responseCode = "409",
                description = "이미 가입된 이메일",
                content = [
                    Content(
                        mediaType = "application/json",
                        examples = [
                            ExampleObject(
                                value = """{"status":"FAIL","code":"EMAIL_ALREADY_EXISTS","message":"이미 사용 중인 이메일입니다."}""",
                            ),
                        ],
                    ),
                ],
            ),
        ],
    )
    @PostMapping("/signup")
    fun signup(
        @RequestBody @Valid request: SignupRequest,
    ): ResponseEntity<ApiResponse<SignupResponse>> {
        val member =
            memberService.signup(
                request.email,
                request.password,
                request.passwordConfirm,
                request.nickname,
                request.grant,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(SignupResponse(member)))
    }

    @Operation(
        summary = "로그인",
        description = """
            이메일과 비밀번호로 로그인합니다.
            액세스 토큰은 응답 본문의 data.accessToken으로 반환합니다.
            리프레시 토큰은 HttpOnly 쿠키로 전달합니다.
        """,
    )
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "로그인 성공. 액세스 토큰과 리프레시 쿠키 발급", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 필수값 누락 또는 이메일 형식 오류", content = [Content()]),
            OpenApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS: 이메일 또는 비밀번호 불일치", content = [Content()]),
            OpenApiResponse(responseCode = "403", description = "ACCOUNT_INACTIVE: 이용이 제한된 계정", content = [Content()]),
        ],
    )
    @PostMapping("/login")
    fun login(
        @RequestBody @Valid request: LoginRequest,
    ): ResponseEntity<ApiResponse<LoginResponse>> {
        val attempt = loginRateLimitService.acquire(request.email)
        if (!attempt.allowed()) {
            return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, attempt.retryAfterSeconds().toString())
                .body(ApiResponse.fail(ErrorCode.LOGIN_RATE_LIMITED))
        }

        val result = authService.login(request.email, request.password)
        val refreshCookie = refreshCookie(result.refreshToken, result.sessionExpiresAt)
        return ResponseEntity
            .ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
            .body(ApiResponse.success(LoginResponse(result.accessToken)))
    }

    @PostMapping("/password-reset")
    fun resetPassword(
        @RequestBody @Valid request: PasswordResetRequest,
    ): ResponseEntity<ApiResponse<Void>> {
        passwordResetService.reset(
            request.email,
            request.grant,
            request.password,
            request.passwordConfirm,
        )
        return ResponseEntity.ok(ApiResponse.success())
    }

    @Operation(
        summary = "액세스 토큰 재발급",
        description = """
            refreshToken 쿠키를 검증하여 새 액세스 토큰을 발급합니다.
            요청 본문과 액세스 토큰은 필요하지 않습니다.
            Swagger에서는 같은 서버에서 먼저 로그인하여 쿠키를 받아야 합니다.
            브라우저의 쿠키 정책에 따라 쿠키가 전송됩니다.
        """,
    )
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "액세스 토큰 재발급 성공", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "401", description = "INVALID_REFRESH_TOKEN: 쿠키 누락 또는 유효하지 않은 리프레시 토큰", content = [Content()]),
            OpenApiResponse(responseCode = "403", description = "ACCOUNT_INACTIVE: 이용이 제한된 계정", content = [Content()]),
        ],
    )
    @PostMapping("/refresh")
    fun refresh(
        @CookieValue(name = "refreshToken", required = false) rawRefreshToken: String?,
    ): ResponseEntity<ApiResponse<LoginResponse>> {
        val result = authService.refresh(rawRefreshToken)
        val refreshCookie = refreshCookie(result.refreshToken, result.sessionExpiresAt)
        return ResponseEntity
            .ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
            .body(ApiResponse.success(LoginResponse(result.accessToken)))
    }

    private fun refreshCookie(
        rawToken: String,
        expiresAt: LocalDateTime,
    ): ResponseCookie {
        val remainingSeconds = max(0, Duration.between(LocalDateTime.now(clock), expiresAt).seconds)
        return ResponseCookie
            .from("refreshToken", rawToken)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path("/api/v1/auth")
            .maxAge(remainingSeconds)
            .build()
    }

    @Operation(
        summary = "로그아웃",
        description = """
            전달된 refreshToken 쿠키에 해당하는 리프레시 토큰을 폐기하고 쿠키를 삭제합니다.
            클라이언트는 보관 중인 액세스 토큰도 삭제해야 합니다.
            이미 발급된 액세스 토큰은 즉시 무효화되지 않고 만료까지 유효합니다.
        """,
    )
    @ApiResponses(value = [OpenApiResponse(responseCode = "204", description = "로그아웃 처리 완료. 응답 본문 없음", content = [Content()])])
    @PostMapping("/logout")
    fun logout(
        @CookieValue(name = "refreshToken", required = false) rawRefreshToken: String?,
    ): ResponseEntity<Void> {
        authService.logout(rawRefreshToken)
        return ResponseEntity
            .noContent()
            .header(HttpHeaders.SET_COOKIE, deleteRefreshCookie().toString())
            .build()
    }

    @Operation(
        summary = "회원탈퇴",
        description = """
            로그인한 정상 상태의 일반 회원이 현재 비밀번호를 확인하고 탈퇴합니다.
            정지 회원과 관리자 계정은 탈퇴할 수 없습니다.
            진행 중인 예약이 있으면 탈퇴할 수 없습니다.
            잔여 크레딧은 소멸하고 리프레시 토큰과 출입 키는 폐기됩니다.
            기존 액세스 토큰은 만료까지 유효합니다.
        """,
    )
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/withdraw")
    fun withdraw(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: MemberWithdrawalRequest,
    ): ResponseEntity<Void> {
        memberWithdrawalService.withdraw(principal.memberId(), request.currentPassword)
        return ResponseEntity
            .noContent()
            .header(HttpHeaders.SET_COOKIE, deleteRefreshCookie().toString())
            .build()
    }

    private fun deleteRefreshCookie(): ResponseCookie =
        ResponseCookie
            .from("refreshToken", "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path("/api/v1/auth")
            .maxAge(0)
            .build()
}
