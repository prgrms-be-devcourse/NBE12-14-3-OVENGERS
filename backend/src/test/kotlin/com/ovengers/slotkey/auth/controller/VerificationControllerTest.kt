@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.auth.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.mail.VerificationMailSender
import com.ovengers.slotkey.support.MySqlTestContainerConfig
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(MySqlTestContainerConfig::class)
internal class VerificationControllerTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var mailSender: VerificationMailSender

    @Test
    fun 오류_응답을_반환해도_오입력이_누적되어_다섯번째에_차단된다() {
        val email = "http-${UUID.randomUUID()}@example.com"
        val deliveredCode: AtomicReference<String> = AtomicReference()

        doAnswer { invocation ->
            deliveredCode.set(invocation.getArgument(2, String::class.java))
            null
        }.whenever(mailSender).sendCode(
            eq(email),
            eq(VerificationPurpose.SIGNUP),
            any<String>(),
        )

        val issued =
            mvc
                .perform(
                    post("/api/v1/auth/verification/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                mapOf("email" to email, "purpose" to "SIGNUP"),
                            ),
                        ),
                ).andExpect(status().isOk())
                .andReturn()

        val challengeId: Long =
            objectMapper
                .readTree(
                    issued.response.contentAsString,
                ).path("data")
                .path("challengeId")
                .asLong()

        val wrongCode: String =
            if (deliveredCode.get()!!.equals("00000000")) {
                "00000001"
            } else {
                "00000000"
            }

        val body: String =
            objectMapper.writeValueAsString(
                mapOf("email" to email, "purpose" to "SIGNUP", "challengeId" to challengeId, "code" to wrongCode),
            )

        for (i in 0..3) {
            mvc
                .perform(
                    post("/api/v1/auth/verification/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isBadRequest())
                .andExpect(
                    jsonPath("$.code")
                        .value("VERIFICATION_CODE_MISMATCH"),
                )
        }

        mvc
            .perform(
                post("/api/v1/auth/verification/check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isTooManyRequests())
            .andExpect(
                jsonPath("$.code")
                    .value("VERIFICATION_TEMPORARILY_BLOCKED"),
            )

        // 차단은 번호 확인뿐 아니라 재발송에도 적용된다.
        mvc
            .perform(
                post("/api/v1/auth/verification/issue")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf("email" to email, "purpose" to "SIGNUP"),
                        ),
                    ),
            ).andExpect(status().isTooManyRequests())
            .andExpect(
                jsonPath("$.code")
                    .value("VERIFICATION_TEMPORARILY_BLOCKED"),
            )
    }
}
