package com.ovengers.slotkey.auth.controller;

import com.ovengers.slotkey.auth.dto.request.LoginRequest;
import com.ovengers.slotkey.auth.dto.request.SignupRequest;
import com.ovengers.slotkey.auth.dto.request.PasswordResetRequest;
import com.ovengers.slotkey.auth.dto.response.LoginResponse;
import com.ovengers.slotkey.auth.dto.response.SignupResponse;
import com.ovengers.slotkey.auth.ratelimit.LoginRateLimitService;
import com.ovengers.slotkey.auth.service.AuthService;
import com.ovengers.slotkey.auth.service.PasswordResetService;
import com.ovengers.slotkey.member.dto.request.MemberWithdrawalRequest;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.service.MemberService;
import com.ovengers.slotkey.member.service.MemberWithdrawalService;
import com.ovengers.slotkey.global.error.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.ovengers.slotkey.auth.dto.internal.LoginResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import com.ovengers.slotkey.global.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.ovengers.slotkey.global.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@Tag(name = "인증", description = "회원가입, 로그인, 토큰 재발급 및 로그아웃 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;
    private final AuthService authService;
    private final MemberWithdrawalService memberWithdrawalService;
    private final PasswordResetService passwordResetService;
    private final LoginRateLimitService loginRateLimitService;
    private final Clock clock;

    @Value("${app.auth.cookie.secure}")
    private boolean cookieSecure;

    @Value("${app.auth.cookie.same-site}")
    private String cookieSameSite;

    // 공통 ApiResponse 형식이 정해지면 추후 반환 형태 수정
    @Operation(
            summary = "회원가입",
            description = """
    이메일 인증 완료 후 발급받은 가입용 증표와
    이메일, 비밀번호, 비밀번호 확인, 닉네임으로 가입합니다.
    증표는 해당 이메일의 가입용이어야 하며 유효 시간 내에 한 번만 사용할 수 있습니다.
    가입 성공 시 회원 정보를 반환합니다. 로그인은 별도로 진행해야 합니다.
    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "회원가입 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "필수 입력값 누락, 입력 형식 오류 또는 비밀번호 확인 불일치",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "입력값 검증 실패",
                                            value = """
                                                {
                                                  "status": "FAIL",
                                                  "code": "VALIDATION_FAILED",
                                                  "message": "비밀번호 확인을 입력해주세요."
                                                }
                                                """
                                    ),
                                    @ExampleObject(
                                            name = "비밀번호 확인 불일치",
                                            value = """
                                                {
                                                  "status": "FAIL",
                                                  "code": "PASSWORD_CONFIRM_MISMATCH",
                                                  "message": "비밀번호가 일치하지 않습니다."
                                                }
                                                """
                                    )
                            }
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "이미 가입된 이메일",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                        {
                                          "status": "FAIL",
                                          "code": "EMAIL_ALREADY_EXISTS",
                                          "message": "이미 사용 중인 이메일입니다."
                                        }
                                        """
                            )
                    )
            )
    })
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(
            @RequestBody @Valid SignupRequest request
    ) {
        Member member = memberService.signup(
                request.email(),
                request.password(),
                request.passwordConfirm(),
                request.nickname(),
                request.grant()
        );
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(new SignupResponse(member)));
    }

    @Operation(
            summary = "로그인",
            description = """
                이메일과 비밀번호로 로그인합니다.
                액세스 토큰은 응답 본문의 data.accessToken으로 반환합니다.
                리프레시 토큰은 HttpOnly 쿠키로 전달합니다.
                """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "로그인 성공. 액세스 토큰과 리프레시 쿠키 발급",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "VALIDATION_FAILED: 필수값 누락 또는 이메일 형식 오류",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "INVALID_CREDENTIALS: 이메일 또는 비밀번호 불일치",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "ACCOUNT_INACTIVE: 이용이 제한된 계정",
                    content = @Content
            )
    })
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @RequestBody @Valid LoginRequest request
    ){
        var attempt = loginRateLimitService.acquire(request.email());
        if (!attempt.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER,
                            Long.toString(attempt.retryAfterSeconds()))
                    .body(ApiResponse.fail(ErrorCode.LOGIN_RATE_LIMITED));
        }

        // 1. 로그인 처리 후 액세스·리프레시 토큰을 받음
        LoginResult result = authService.login(
                request.email(),
                request.password()
        );

        // 2. 리프레시 토큰을 담은 쿠키 구성
        ResponseCookie refreshCookie = refreshCookie(
                result.refreshToken(), result.sessionExpiresAt()
        );

        // 3. 리프레시는 쿠키로, 액세스는 응답 본문으로 전달
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(ApiResponse.success(
                        new LoginResponse(result.accessToken())
                ));
    }

    @PostMapping("/password-reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @RequestBody @Valid PasswordResetRequest request
    ) {
        passwordResetService.reset(
                request.email(),
                request.grant(),
                request.password(),
                request.passwordConfirm()
        );
        return ResponseEntity.ok(ApiResponse.success());
    }


    // 엑세스 토큰 갱신 api
    @Operation(
            summary = "액세스 토큰 재발급",
            description = """
                refreshToken 쿠키를 검증하여 새 액세스 토큰을 발급합니다.
                요청 본문과 액세스 토큰은 필요하지 않습니다.
                Swagger에서는 같은 서버에서 먼저 로그인하여 쿠키를 받아야 합니다.
                브라우저의 쿠키 정책에 따라 쿠키가 전송됩니다.
                """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "액세스 토큰 재발급 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "INVALID_REFRESH_TOKEN: 쿠키 누락 또는 유효하지 않은 리프레시 토큰",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "ACCOUNT_INACTIVE: 이용이 제한된 계정",
                    content = @Content
            )
    })
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @CookieValue(name = "refreshToken", required = false)
            String rawRefreshToken
    ) {
        var result = authService.refresh(rawRefreshToken);
        ResponseCookie refreshCookie = refreshCookie(
                result.refreshToken(), result.sessionExpiresAt()
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(ApiResponse.success(
                        new LoginResponse(result.accessToken())
                ));
    }

    private ResponseCookie refreshCookie(String rawToken, LocalDateTime expiresAt) {
        long remainingSeconds = Math.max(
                0,
                Duration.between(LocalDateTime.now(clock), expiresAt).getSeconds()
        );
        return ResponseCookie
                .from("refreshToken", rawToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/api/v1/auth")
                .maxAge(remainingSeconds)
                .build();
    }

    // 로그아웃 메서드
    @Operation(
            summary = "로그아웃",
            description = """
                전달된 refreshToken 쿠키에 해당하는 리프레시 토큰을 폐기하고 쿠키를 삭제합니다.
                클라이언트는 보관 중인 액세스 토큰도 삭제해야 합니다.
                이미 발급된 액세스 토큰은 즉시 무효화되지 않고 만료까지 유효합니다.
                """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "204",
                    description = "로그아웃 처리 완료. 응답 본문 없음",
                    content = @Content
            )
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refreshToken", required = false)
            String rawRefreshToken
    ) {
        // 1. DB의 리프레시 토큰 폐기
        authService.logout(rawRefreshToken);

        // 2. 기존 쿠키를 삭제하기 위한 쿠키 설정
        ResponseCookie deleteCookie = ResponseCookie
                .from("refreshToken", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/api/v1/auth")
                .maxAge(0)
                .build();

        // 3. 쿠키 삭제 헤더와 본문 없는 성공 응답
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, deleteCookie.toString())
                .build();
    }

    @Operation(
            summary = "회원탈퇴",
            description = """
                로그인한 정상 상태의 일반 회원이 현재 비밀번호를 확인하고 탈퇴합니다.
                정지 회원과 관리자 계정은 탈퇴할 수 없습니다.
                진행 중인 예약이 있으면 탈퇴할 수 없습니다.
                잔여 크레딧은 소멸하고 리프레시 토큰과 출입 키는 폐기됩니다.
                기존 액세스 토큰은 만료까지 유효합니다.
                """
    )
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/withdraw")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody MemberWithdrawalRequest request
    ) {
        memberWithdrawalService.withdraw(
                principal.memberId(),
                request.currentPassword()
        );

        ResponseCookie deleteCookie = ResponseCookie
                .from("refreshToken", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/api/v1/auth")
                .maxAge(0)
                .build();

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, deleteCookie.toString())
                .build();
    }
}
