package com.ovengers.slotkey.global.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class CorsConfig {
    @Value("\${app.cors.allowed-origins}")
    private var allowedOrigins: List<String>? = null

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration()

        // 허용 오리진 (환경변수로 관리)
        config.allowedOrigins = allowedOrigins

        // 허용 HTTP 메서드
        config.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

        // 허용 요청 헤더 (Authorization, Idempotency-Key 등 프로젝트 필수 헤더 포함)
        config.allowedHeaders =
            listOf(
                "Authorization",
                "Content-Type",
                "Idempotency-Key",
            )

        // 프론트엔드에서 읽을 수 있도록 노출할 응답 헤더
        config.exposedHeaders = listOf("Authorization")

        // 쿠키/인증정보 포함 허용
        config.allowCredentials = true

        // preflight 캐시 시간 (1시간)
        config.maxAge = 3600L

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", config)
        return source
    }
}
