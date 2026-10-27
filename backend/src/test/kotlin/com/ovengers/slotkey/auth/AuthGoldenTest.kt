package com.ovengers.slotkey.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.auth.dto.response.LoginResponse
import com.ovengers.slotkey.auth.dto.response.SignupResponse
import com.ovengers.slotkey.auth.dto.response.VerificationCheckResponse
import com.ovengers.slotkey.auth.dto.response.VerificationIssueResponse
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.support.SurveyGoldenAssertions.assertGolden
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

/**
 * 인증 응답 DTO의 JSON 계약을 정적 골든으로 고정한다.
 * 기준 JSON은 DTO 전환 직전 Java 커밋 f2273a5에서 같은 입력으로 생성해 검토한 값이다.
 */
@JsonTest
class AuthGoldenTest {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("SignupResponse는 회원 엔티티의 ID·이메일·닉네임만 담는다")
    fun signupResponse() {
        assertGolden(objectMapper, "auth/signup-response", SignupResponse(member()))
    }

    @Test
    @DisplayName("LoginResponse는 accessToken 하나만 본문에 담는다")
    fun loginResponse() {
        assertGolden(objectMapper, "auth/login-response", LoginResponse("header.payload.signature"))
    }

    @Test
    @DisplayName("VerificationIssueResponse는 challengeId와 만료·재발송 가능 시각을 유지한다")
    fun verificationIssueResponse() {
        assertGolden(
            objectMapper,
            "auth/verification-issue-response",
            VerificationIssueResponse(41L, BASE.plusMinutes(3), BASE.plusSeconds(30)),
        )
    }

    @Test
    @DisplayName("VerificationCheckResponse는 grant 원문과 만료 시각을 유지한다")
    fun verificationCheckResponse() {
        assertGolden(
            objectMapper,
            "auth/verification-check-response",
            VerificationCheckResponse("Zm9vYmFyLWdyYW50LXRva2VuLWJhc2U2NHVybA", BASE.plusMinutes(5)),
        )
    }

    private fun member(): Member =
        Member("golden.user@example.com", "골든회원").also {
            ReflectionTestUtils.setField(it, "id", 7L)
            ReflectionTestUtils.setField(it, "balance", 300000)
            ReflectionTestUtils.setField(it, "createdAt", BASE)
        }

    private companion object {
        val BASE: LocalDateTime = LocalDateTime.of(2026, 10, 20, 9, 30, 15)
    }
}
