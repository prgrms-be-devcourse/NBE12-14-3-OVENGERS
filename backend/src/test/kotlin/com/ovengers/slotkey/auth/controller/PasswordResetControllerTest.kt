@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.auth.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.mail.VerificationMailSender
import com.ovengers.slotkey.auth.service.VerificationChallengeService
import com.ovengers.slotkey.auth.service.VerificationCheckService
import com.ovengers.slotkey.member.service.MemberService
import com.ovengers.slotkey.support.MySqlTestContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(MySqlTestContainerConfig::class)
internal class PasswordResetControllerTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var memberService: MemberService

    @Autowired
    private lateinit var challengeService: VerificationChallengeService

    @Autowired
    private lateinit var checkService: VerificationCheckService

    @MockBean
    private lateinit var mailSender: VerificationMailSender

    @Test
    fun 비로그인_비밀번호_재설정_API가_동작하고_기존_AT는_폐기된다() {
        val email = "reset-http-${UUID.randomUUID()}@example.com"
        val signupGrant: String = issueGrant(email, VerificationPurpose.SIGNUP)
        memberService.signup(
            email,
            "Old1234!",
            "Old1234!",
            "테스트회원",
            signupGrant,
        )

        val accessToken: String = login(email, "Old1234!")
        val resetGrant: String = issueGrant(email, VerificationPurpose.PASSWORD_RESET)

        mvc
            .perform(
                post("/api/v1/auth/password-reset")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("email" to email, "grant" to resetGrant, "password" to "New1234!", "passwordConfirm" to "New1234!"),
                        ),
                    ),
            ).andExpect(status().isOk())

        mvc
            .perform(
                get("/api/v1/members/me")
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isUnauthorized())
    }

    private fun login(
        email: String,
        password: String,
    ): String {
        val response: String =
            mvc
                .perform(
                    post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                mapOf("email" to email, "password" to password),
                            ),
                        ),
                ).andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString

        val data: JsonNode = objectMapper.readTree(response).path("data")
        val accessToken: String = data.path("accessToken").asText()
        assertThat(accessToken).isNotBlank()
        return accessToken
    }

    private fun issueGrant(
        email: String,
        purpose: VerificationPurpose,
    ): String {
        val deliveredCode: AtomicReference<String> = AtomicReference()
        doAnswer { invocation ->
            deliveredCode.set(invocation.getArgument(2, String::class.java))
            null
        }.whenever(mailSender).sendCode(eq(email), eq(purpose), any<String>())

        val issued = challengeService.issue(email, purpose)
        return checkService
            .verify(
                email,
                purpose,
                issued.challengeId,
                deliveredCode.get()!!,
            ).grant!!
    }
}
