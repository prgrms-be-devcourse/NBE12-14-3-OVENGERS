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
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
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
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/favicon.ico")
                    .permitAll()
                    .requestMatchers(
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                    ).permitAll()
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
                    .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/tosspayments")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/spaces/**", "/api/v1/space-images/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/stats/**")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated()
            }.csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(customAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling { exception ->
                exception
                    .authenticationEntryPoint { _, response, _ ->
                        response.status = ErrorCode.AUTHENTICATION_REQUIRED.httpStatus.value()
                        response.contentType = "application/json"
                        response.characterEncoding = "UTF-8"
                        objectMapper.writeValue(response.writer, ApiResponse.fail<Void>(ErrorCode.AUTHENTICATION_REQUIRED))
                    }.accessDeniedHandler { _, response, _ ->
                        response.status = ErrorCode.ACCESS_DENIED.httpStatus.value()
                        response.contentType = "application/json"
                        response.characterEncoding = "UTF-8"
                        objectMapper.writeValue(response.writer, ApiResponse.fail<Void>(ErrorCode.ACCESS_DENIED))
                    }
            }

        return http.build()
    }
}
