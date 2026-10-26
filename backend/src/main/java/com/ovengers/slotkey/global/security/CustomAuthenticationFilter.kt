package com.ovengers.slotkey.global.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.member.entity.MemberRole
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.LocalDateTime

class CustomAuthenticationFilter(
    private val jwtProvider: JwtProvider,
    private val objectMapper: ObjectMapper,
    private val authSessionService: AuthSessionService,
    private val inactiveMemberAccessLogService: InactiveMemberAccessLogService,
    private val clock: Clock,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method == "POST" && request.servletPath in PUBLIC_AUTH_PATHS

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authorization = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (authorization == null) {
            filterChain.doFilter(request, response)
            return
        }
        if (!authorization.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) {
            writeError(response, ErrorCode.INVALID_ACCESS_TOKEN)
            return
        }

        val accessToken = authorization.substring(7).trim()
        if (accessToken.isEmpty()) {
            writeError(response, ErrorCode.INVALID_ACCESS_TOKEN)
            return
        }

        val payload: Map<String, Any?> =
            jwtProvider.payloadOrNull(accessToken) ?: run {
                writeError(response, ErrorCode.INVALID_ACCESS_TOKEN)
                return
            }
        val principal =
            extractPrincipal(payload) ?: run {
                writeError(response, ErrorCode.INVALID_ACCESS_TOKEN)
                return
            }
        val sessionId = payload["sid"] as? String
        if (sessionId == null) {
            writeError(response, ErrorCode.INVALID_ACCESS_TOKEN)
            return
        }

        val memberId = principal.memberId
        if (memberId == null) {
            writeError(response, ErrorCode.INVALID_ACCESS_TOKEN)
            return
        }

        val sessionResult = authSessionService.validate(sessionId, memberId)

        if (sessionResult == AuthSessionService.ValidationResult.UNAVAILABLE) {
            writeError(response, ErrorCode.AUTHENTICATION_SERVICE_UNAVAILABLE)
            return
        }
        if (sessionResult == AuthSessionService.ValidationResult.MEMBER_INACTIVE) {
            recordInactiveMemberAccessAttempt(request, principal.memberId)
            writeError(response, ErrorCode.INVALID_ACCESS_TOKEN)
            return
        }
        if (sessionResult != AuthSessionService.ValidationResult.VALID) {
            writeError(response, ErrorCode.INVALID_ACCESS_TOKEN)
            return
        }

        val authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        filterChain.doFilter(request, response)
    }

    private fun recordInactiveMemberAccessAttempt(
        request: HttpServletRequest,
        memberId: Long,
    ) {
        if (request.method != "POST" || request.servletPath != "/api/v1/door-access/verify") return

        try {
            inactiveMemberAccessLogService.record(memberId, LocalDateTime.now(clock))
        } catch (exception: RuntimeException) {
            log.error(
                "비활성 회원 출입 로그 저장 실패 - memberId: {}, causeType: {}",
                memberId,
                exception.javaClass.simpleName,
            )
        }
    }

    private fun extractPrincipal(payload: Map<String, Any?>): AuthPrincipal? {
        val id = payload["id"] as? Number ?: return null
        val email = payload["email"] as? String ?: return null
        val role = payload["role"] as? String ?: return null
        if (email.isBlank() || payload["exp"] !is Number) return null

        return try {
            val memberId = id.toString().toLong()
            if (memberId <= 0) null else AuthPrincipal(memberId, email, MemberRole.valueOf(role))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun writeError(
        response: HttpServletResponse,
        errorCode: ErrorCode,
    ) {
        SecurityContextHolder.clearContext()
        response.status = errorCode.httpStatus.value()
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        objectMapper.writeValue(response.writer, ApiResponse.fail<Void>(errorCode))
    }

    companion object {
        private val log = LoggerFactory.getLogger(CustomAuthenticationFilter::class.java)
        private val PUBLIC_AUTH_PATHS =
            setOf(
                "/api/v1/auth/signup",
                "/api/v1/auth/login",
                "/api/v1/auth/password-reset",
                "/api/v1/auth/refresh",
                "/api/v1/auth/logout",
            )
    }
}
