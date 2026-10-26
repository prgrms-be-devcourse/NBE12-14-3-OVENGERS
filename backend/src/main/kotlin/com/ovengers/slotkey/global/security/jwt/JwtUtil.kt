package com.ovengers.slotkey.global.security.jwt

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.time.Clock
import java.time.LocalDateTime
import java.util.Date

// TODO: Java 호출부·테스트 전환 후 생성자 사용 여부를 확인하고 object 전환과 @JvmStatic 제거를 검토한다.
class JwtUtil {
    companion object {
        @JvmStatic
        fun createToken(
            secretKey: String,
            expireMillis: Long,
            body: Map<String, Any?>?,
        ): String = createToken(secretKey, expireMillis, body, null, Clock.systemUTC())

        @JvmStatic
        fun createToken(
            secretKey: String,
            expireMillis: Long,
            body: Map<String, Any?>?,
            latestExpiration: LocalDateTime?,
            clock: Clock,
        ): String {
            val key = Keys.hmacShaKeyFor(secretKey.toByteArray(Charsets.UTF_8))
            val issuedInstant = clock.instant()
            var expirationInstant = issuedInstant.plusMillis(expireMillis)
            if (latestExpiration != null) {
                val sessionExpiration = latestExpiration.atZone(clock.zone).toInstant()
                if (sessionExpiration.isBefore(expirationInstant)) {
                    expirationInstant = sessionExpiration
                }
            }

            return Jwts
                .builder()
                .claims(body)
                .issuedAt(Date.from(issuedInstant))
                .expiration(Date.from(expirationInstant))
                .signWith(key)
                .compact()
        }

        @JvmStatic
        fun payloadOrNull(
            token: String?,
            secretKey: String,
        ): Map<String, Any?>? = payloadOrNull(token, secretKey, Clock.systemUTC())

        @JvmStatic
        fun payloadOrNull(
            token: String?,
            secretKey: String,
            clock: Clock,
        ): Map<String, Any?>? {
            // 키 설정 오류는 기존처럼 호출자에게 전파하고, 토큰 해석 오류만 null로 처리한다.
            val key = Keys.hmacShaKeyFor(secretKey.toByteArray(Charsets.UTF_8))
            return try {
                Jwts
                    .parser()
                    .verifyWith(key)
                    .clock { Date.from(clock.instant()) }
                    .build()
                    .parseSignedClaims(token)
                    .payload
            } catch (_: JwtException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
