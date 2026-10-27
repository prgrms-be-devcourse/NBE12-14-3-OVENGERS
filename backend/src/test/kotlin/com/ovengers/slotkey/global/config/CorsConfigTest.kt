package com.ovengers.slotkey.global.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource

class CorsConfigTest {
    @Test
    @DisplayName("CORS 설정이 명세에 맞게 등록된다")
    fun corsConfigurationSource_configuredCorrectly() {
        val corsConfig = CorsConfig()
        ReflectionTestUtils.setField(corsConfig, "allowedOrigins", listOf("http://localhost:3000"))

        val source: CorsConfigurationSource = corsConfig.corsConfigurationSource()
        val request = MockHttpServletRequest("GET", "/api/v1/spaces")
        val config: CorsConfiguration? = source.getCorsConfiguration(request)

        assertThat(config).isNotNull()
        val corsConfiguration = requireNotNull(config)
        assertThat(corsConfiguration.allowedOrigins).containsExactly("http://localhost:3000")
        assertThat(corsConfiguration.allowedMethods).contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        assertThat(corsConfiguration.allowedHeaders).contains("Authorization", "Content-Type", "Idempotency-Key")
        assertThat(corsConfiguration.exposedHeaders).contains("Authorization")
        assertThat(corsConfiguration.allowCredentials).isTrue()
        assertThat(corsConfiguration.maxAge).isEqualTo(3600L)
    }
}
