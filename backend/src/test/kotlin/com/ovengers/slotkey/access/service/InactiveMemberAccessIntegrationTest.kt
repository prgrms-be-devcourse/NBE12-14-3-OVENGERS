package com.ovengers.slotkey.access.service

import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.member.service.AdminMemberService
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class InactiveMemberAccessIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var adminMemberService: AdminMemberService

    @Autowired
    private lateinit var doorAccessTokenService: DoorAccessTokenService

    @Test
    @DisplayName("정지 회원의 기존 JWT 출입 요청은 거절 로그를 저장하고 체크인하지 않는다")
    fun shouldRecordDeniedAccessAfterMemberSuspension() {
        val memberId = createMember(0)
        val adminId = createMember(0)
        val spaceId = createSpace()

        // 정지를 수행할 관리자 계정 준비
        jdbcTemplate.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", adminId)

        // 현재 이용 가능한 확정 예약 준비
        val startAt = now()
        val reservationId =
            createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, startAt, startAt.plusMinutes(30))
        val member = memberRepository.findById(memberId).orElseThrow()

        // 정지 전에 정상 로그인 세션과 출입키 발급
        val accessToken = issueSessionBoundAccessToken(member, jwtProvider)
        doorAccessTokenService.issue(memberId, reservationId)

        // 정지 전에 활성 출입키가 발급됐는지 확인
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM door_access_token
                WHERE reservation_id = ?
                  AND revoked_at IS NULL
                """.trimIndent(),
                Int::class.javaObjectType,
                reservationId,
            ),
        ).isEqualTo(1)

        // 실제 정지 서비스 호출로 회원 정지 및 세션 폐기
        adminMemberService.suspend(memberId, "출입 제한 테스트", adminId)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth_session
                WHERE member_id = ?
                  AND revoked_at IS NOT NULL
                """.trimIndent(),
                Int::class.javaObjectType,
                memberId,
            ),
        ).isEqualTo(1)

        // 인증 단계에서 차단되므로 출입키 본문 검증까지 진행되지 않음
        mockMvc
            .perform(
                post("/api/v1/door-access/verify")
                    .servletPath("/api/v1/door-access/verify")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "spaceId": $spaceId,
                          "token": "unused-after-authentication-rejection"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))

        // 실제 DB에 회원 비활성 거절 로그 1건 저장
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM door_access_log
                WHERE actor_member_id = ?
                  AND result = 'DENY'
                  AND reason_code = 'MEMBER_INACTIVE'
                  AND reservation_id IS NULL
                  AND requested_space_id IS NULL
                  AND attempted_at IS NOT NULL
                """.trimIndent(),
                Int::class.javaObjectType,
                memberId,
            ),
        ).isEqualTo(1)

        // 출입 허용 로그는 생성되지 않음
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM door_access_log
                WHERE actor_member_id = ?
                  AND result = 'ALLOW'
                """.trimIndent(),
                Int::class.javaObjectType,
                memberId,
            ),
        ).isZero()

        // 예약 상태와 최초 체크인 시각 유지
        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED")
        assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM reservation
                WHERE id = ?
                  AND checked_in_at IS NULL
                """.trimIndent(),
                Int::class.javaObjectType,
                reservationId,
            ),
        ).isEqualTo(1)
    }
}
