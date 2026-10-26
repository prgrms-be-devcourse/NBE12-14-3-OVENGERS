package com.ovengers.slotkey.member.controller

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.error.GlobalExceptionHandler
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.member.dto.response.MemberResponse
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.service.MemberService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

class MemberControllerTest {
    private lateinit var memberService: MemberService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        memberService = mock(MemberService::class.java)
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(MemberController(memberService))
                .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
        val principal = AuthPrincipal(1L, "member@example.com", MemberRole.USER)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"nickname\":null}"])
    fun missingNicknamePreservesValidationMessage(body: String) {
        mockMvc
            .perform(patch("/api/v1/members/me").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("닉네임을 입력해주세요."))
        verifyNoInteractions(memberService)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "a", "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"])
    fun invalidNicknameIsRejectedBeforeService(nickname: String) {
        mockMvc
            .perform(
                patch("/api/v1/members/me")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nickname":"$nickname"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        verifyNoInteractions(memberService)
    }

    @Test
    fun validNicknameIsTrimmedAndResponseIsPreserved() {
        val response = MemberResponse(1L, "member@example.com", "새닉네임", MemberRole.USER, 5000, LocalDateTime.of(2026, 10, 1, 0, 0))
        `when`(memberService.updateNickname(1L, "새닉네임")).thenReturn(response)
        mockMvc
            .perform(
                patch("/api/v1/members/me")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nickname":"  새닉네임  "}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.memberId").value(1))
            .andExpect(jsonPath("$.data.nickname").value("새닉네임"))
            .andExpect(jsonPath("$.data.balance").value(5000))
        verify(memberService).updateNickname(1L, "새닉네임")
    }

    @Test
    fun missingMemberPreservesAuthenticationError() {
        `when`(memberService.updateNickname(1L, "새닉네임"))
            .thenThrow(BusinessException(ErrorCode.AUTHENTICATION_REQUIRED))
        mockMvc
            .perform(
                patch("/api/v1/members/me")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nickname":"새닉네임"}"""),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
    }
}
