package com.ovengers.slotkey.reservation.controller

import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

@AutoConfigureMockMvc
@Transactional
class AdminReservationSecurityIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Test
    @DisplayName("미인증 사용자가 관리자 예약 목록을 요청하면 401 Unauthorized와 AUTHENTICATION_REQUIRED를 반환한다")
    fun getReservations_unauthenticated_returns401() {
        mockMvc
            .perform(
                get(ADMIN_RESERVATIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("\$.status").value("FAIL"))
            .andExpect(jsonPath("\$.code").value("AUTHENTICATION_REQUIRED"))
    }

    @Test
    @DisplayName("일반 회원(USER)이 관리자 예약 목록을 요청하면 403 Forbidden과 ACCESS_DENIED를 반환한다")
    fun getReservations_userRole_returns403() {
        val user: Member = memberRepository.save(Member("user@slotkey.test", "일반회원"))
        val token: String = issueSessionBoundAccessToken(user, jwtProvider)

        mockMvc
            .perform(
                get(ADMIN_RESERVATIONS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("\$.status").value("FAIL"))
            .andExpect(jsonPath("\$.code").value("ACCESS_DENIED"))
    }

    @Test
    @DisplayName("관리자(ADMIN)가 관리자 예약 목록을 요청하면 200 OK와 PageResponse를 반환한다")
    fun getReservations_adminRole_returns200() {
        var admin: Member = Member("admin@slotkey.test", "관리자")
        ReflectionTestUtils.setField(admin, "role", MemberRole.ADMIN)
        admin = memberRepository.save(admin)
        val token: String = issueSessionBoundAccessToken(admin, jwtProvider)

        mockMvc
            .perform(
                get(ADMIN_RESERVATIONS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("\$.status").value("SUCCESS"))
            .andExpect(jsonPath("\$.code").value("OK"))
            .andExpect(jsonPath("\$.data.content").isArray())
            .andExpect(jsonPath("\$.data.page").value(0))
            .andExpect(jsonPath("\$.data.size").value(20))
    }

    companion object {
        private val ADMIN_RESERVATIONS_PATH: String = "/api/v1/admin/reservations"
    }
}
