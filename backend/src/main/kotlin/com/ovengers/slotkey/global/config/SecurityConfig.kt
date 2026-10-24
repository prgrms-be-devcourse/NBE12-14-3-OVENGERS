package com.ovengers.slotkey.global.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.security.CustomAuthenticationFilter
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import java.time.Clock

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtProvider: JwtProvider,
    private val objectMapper: ObjectMapper,
    private val authSessionService: AuthSessionService,
    private val inactiveMemberAccessLogService: InactiveMemberAccessLogService,
    private val clock: Clock,
) {
    @Bean
    @Throws(Exception::class)
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        // JWT 인증을 처리할 필터 생성
        val customAuthenticationFilter =
            CustomAuthenticationFilter(
                jwtProvider,
                objectMapper,
                authSessionService,
                inactiveMemberAccessLogService,
                clock,
            )

        http
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests { auth ->
                auth
                    // 오류 응답을 만드는 내부 ERROR 디스패치가 인증 실패로 바뀌지 않도록 허용한다.
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/favicon.ico")
                    .permitAll()
                    // Swagger UI 및 API 문서 접근 허용
                    .requestMatchers(
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                    ).permitAll()
                    // 액세스 토큰 없이 접근 가능한 API
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/signup",
                        "/api/v1/auth/login",
                        "/api/v1/auth/password-reset",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/verification/issue",
                        "/api/v1/auth/verification/check",
                    ).permitAll()
                    // 토스페이먼츠 웹훅: PG가 호출해 JWT를 붙일 수 없다. 본문을 믿지 않고
                    // PG 조회로만 충전을 확정하므로 이 POST 하나만 연다(pg-charge.md 5단계 결정 1).
                    .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/tosspayments")
                    .permitAll()
                    // 공간 목록, 상세, 슬롯 가용성 비회원 조회 허용
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/spaces/**",
                        "/api/v1/space-images/**",
                    ).permitAll()
                    // 베스트 공간 랭킹: 순매출이 빠진 확정 집계라 비로그인 방문자에게도 연다(api-spec §14-2).
                    // 관리자 랭킹(/api/v1/admin/stats/**)은 아래 ADMIN 규칙을 그대로 따른다.
                    .requestMatchers(HttpMethod.GET, "/api/v1/stats/**")
                    .permitAll()
                    // 관리자 권한 필요
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    // 나머지 요청은 인증 필요
                    .anyRequest()
                    .authenticated()
            }.csrf { csrf -> csrf.disable() }
            .formLogin { formLogin -> formLogin.disable() }
            .httpBasic { httpBasic -> httpBasic.disable() }
            // 인증 정보를 HTTP 세션에 저장하지 않음
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            // JWT 인증 필터를 시큐리티 필터 체인에 추가
            .addFilterBefore(
                customAuthenticationFilter,
                UsernamePasswordAuthenticationFilter::class.java,
            ).exceptionHandling { exception ->
                exception
                    // 인증되지 않은 사용자가 보호된 API에 접근
                    .authenticationEntryPoint { _, response, _ ->
                        response.status =
                            ErrorCode.AUTHENTICATION_REQUIRED
                                .httpStatus
                                .value()
                        response.contentType = "application/json"
                        response.characterEncoding = "UTF-8"

                        objectMapper.writeValue(
                            response.writer,
                            ApiResponse.fail<Unit>(
                                ErrorCode.AUTHENTICATION_REQUIRED,
                            ),
                        )
                    }
                    // 인증됐지만 필요한 권한이 없음
                    .accessDeniedHandler { _, response, _ ->
                        response.status =
                            ErrorCode.ACCESS_DENIED
                                .httpStatus
                                .value()
                        response.contentType = "application/json"
                        response.characterEncoding = "UTF-8"

                        objectMapper.writeValue(
                            response.writer,
                            ApiResponse.fail<Unit>(ErrorCode.ACCESS_DENIED),
                        )
                    }
            }

        return http.build()
    }
}
