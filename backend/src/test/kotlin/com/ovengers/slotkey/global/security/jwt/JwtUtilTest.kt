package com.ovengers.slotkey.global.security.jwt

import io.jsonwebtoken.security.WeakKeyException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class JwtUtilTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC)
    private val secret = "step2-jwt-test-secret-key-0123456789abcdef0123456789"

    @Test
    @DisplayName("다른 키로 서명하거나 본문을 변조한 JWT는 거절한다")
    fun rejectsWrongSignatureAndTamperedClaims() {
        val token = JwtUtil.createToken(secret, 300_000L, mapOf("role" to "USER"), null, clock)
        val otherSecret = "another-jwt-secret-key-0123456789abcdef0123456789"
        assertThat(JwtUtil.payloadOrNull(token, otherSecret, clock)).isNull()

        val parts = token.split('.')
        val claims = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
        val changedClaims = claims.replace("USER", "ADMIN")
        assertThat(changedClaims).isNotEqualTo(claims)
        val changedBody = Base64.getUrlEncoder().withoutPadding().encodeToString(changedClaims.toByteArray(StandardCharsets.UTF_8))
        val tamperedToken = "${parts[0]}.$changedBody.${parts[2]}"
        assertThat(JwtUtil.payloadOrNull(tamperedToken, secret, clock)).isNull()
    }

    @Test
    @DisplayName("주입한 Clock 기준으로 만료 직전에는 허용하고 만료 직후에는 거절한다")
    fun checksExpirationUsingInjectedClock() {
        val token = JwtUtil.createToken(secret, 300_000L, mapOf("id" to 1L), null, clock)
        val beforeExpiration = Clock.offset(clock, Duration.ofMillis(299_999L))
        val afterExpiration = Clock.offset(clock, Duration.ofMillis(300_001L))
        assertThat(JwtUtil.payloadOrNull(token, secret, beforeExpiration)).isNotNull()
        assertThat(JwtUtil.payloadOrNull(token, secret, afterExpiration)).isNull()
    }

    @Test
    @DisplayName("누락·빈 문자열·공백·형식이 잘못된 JWT는 예외 대신 null을 반환한다")
    fun returnsNullForInvalidTokenInput() {
        for (token in listOf(null, "", "   ", "not-a-jwt", "invalid.payload.signature")) {
            assertThat(JwtUtil.payloadOrNull(token, secret, clock)).isNull()
        }
    }

    @Test
    @DisplayName("짧은 서명 키는 토큰 오류로 숨기지 않고 설정 오류로 전파한다")
    fun propagatesWeakSigningKey() {
        assertThatThrownBy { JwtUtil.createToken("short-key", 300_000L, emptyMap(), null, clock) }
            .isInstanceOf(WeakKeyException::class.java)
        assertThatThrownBy { JwtUtil.payloadOrNull("not-a-jwt", "short-key", clock) }
            .isInstanceOf(WeakKeyException::class.java)
    }

    @Test
    @DisplayName("Clock 인자 없는 JWT 오버로드도 클레임을 보존하고 만료된 토큰을 거절한다")
    fun defaultClockOverloadsPreserveClaimsAndRejectExpiredToken() {
        val token = JwtUtil.createToken(secret, 86_400_000L, mapOf("id" to 2_147_483_648L, "sid" to "default-clock-session"))
        val payload = JwtUtil.payloadOrNull(token, secret)
        assertThat(payload).isNotNull()
        assertThat((payload!!["id"] as Number).toLong()).isEqualTo(2_147_483_648L)
        assertThat(payload["sid"]).isEqualTo("default-clock-session")

        val expiredClock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)
        val expiredToken = JwtUtil.createToken(secret, 300_000L, mapOf("id" to 1L), null, expiredClock)
        assertThat(JwtUtil.payloadOrNull(expiredToken, secret)).isNull()
    }
}
