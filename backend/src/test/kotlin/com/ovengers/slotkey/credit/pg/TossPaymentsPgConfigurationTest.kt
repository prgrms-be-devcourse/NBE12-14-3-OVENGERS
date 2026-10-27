package com.ovengers.slotkey.credit.pg

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.net.URI
import java.time.Duration

class TossPaymentsPgConfigurationTest {
    @Test
    @DisplayName("토스 HTTP 요청 팩토리는 연결 3초와 읽기 60초 타임아웃을 설정한다")
    fun requestFactory_usesDecidedTimeouts() {
        val properties =
            TossPaymentsProperties(
                URI.create("https://api.tosspayments.com"),
                "test-secret",
                Duration.ofSeconds(3),
                Duration.ofSeconds(60),
            )

        val factory =
            TossPaymentsPgConfiguration.requestFactory(properties)

        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout"))
            .isEqualTo(3_000)
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout"))
            .isEqualTo(60_000)
    }
}
