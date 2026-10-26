package com.ovengers.slotkey.global.security.jwt

import com.ovengers.slotkey.member.entity.Member
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

@Component
class JwtProvider(
    private val clock: Clock,
) {
    @field:Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    @field:Value("\${custom.jwt.expire-millis}")
    private var expireMillis: Long = 0

    fun genAccessToken(
        member: Member,
        sessionId: String?,
        sessionExpiresAt: LocalDateTime?,
    ): String =
        JwtUtil.createToken(
            secretKey,
            expireMillis,
            // Map.of의 null 거부 동작을 유지한다.
            java.util.Map.of(
                "id",
                member.id,
                "email",
                member.email,
                "role",
                member.role.name,
                "sid",
                sessionId,
            ),
            sessionExpiresAt,
            clock,
        )

    fun payloadOrNull(token: String?): Map<String, Any?>? = JwtUtil.payloadOrNull(token, secretKey, clock)
}
