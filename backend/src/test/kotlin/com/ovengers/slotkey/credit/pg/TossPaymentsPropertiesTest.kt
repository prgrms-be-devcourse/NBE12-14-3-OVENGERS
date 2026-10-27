package com.ovengers.slotkey.credit.pg

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class TossPaymentsPropertiesTest {
    @Test
    @DisplayName("설정 문자열 표현은 토스 시크릿 키를 노출하지 않는다")
    fun toString_redactsSecretKey() {
        val properties =
            TossPaymentsProperties(
                URI.create("https://api.tosspayments.com"),
                "secret-value-must-not-leak",
                Duration.ofSeconds(3),
                Duration.ofSeconds(60),
            )

        assertThat(properties.toString())
            .contains("secretKey=***")
            .doesNotContain("secret-value-must-not-leak")
    }
}
