package com.ovengers.slotkey.access.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.support.SettableClockConfig
import com.ovengers.slotkey.support.SettableClockConfig.SettableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@AutoConfigureMockMvc
@Import(SettableClockConfig::class)
class DoorAccessSecurityIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var testClock: SettableClock

    private var memberId = 0L
    private var spaceId = 0L
    private var reservationId = 0L
    private lateinit var accessToken: String
    private lateinit var doorToken: String

    @BeforeEach
    fun setUp() {
        testClock.set(LocalDateTime.of(2026, 10, 26, 14, 0).atZone(SettableClockConfig.KST))
        memberId = createMember(0)
        spaceId = createSpace()
        reservationId = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, now(), now().plusMinutes(30))
        accessToken = issueSessionBoundAccessToken(memberRepository.findById(memberId).orElseThrow(), jwtProvider)
        val issued =
            mockMvc
                .perform(post("/api/v1/reservations/$reservationId/door-token").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.reservationId").value(reservationId))
                .andReturn()
        doorToken =
            objectMapper
                .readTree(issued.response.contentAsString)
                .path("data")
                .path("token")
                .asText()
        assertThat(doorToken).isNotBlank()
    }

    @Test
    @DisplayName("실제 세션으로 최초 체크인·재입장하면 예약 상태와 최초 체크인 이력이 DB에 반영된다")
    fun checkInAndReentry() {
        val checkedInAt = now()
        verifyAccess()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.result").value("ALLOW"))
            .andExpect(jsonPath("$.data.firstCheckIn").value(true))
        assertThat(statusOf(reservationId)).isEqualTo("IN_USE")
        assertThat(
            jdbcTemplate.queryForObject("SELECT checked_in_at FROM reservation WHERE id = ?", LocalDateTime::class.java, reservationId),
        ).isEqualTo(checkedInAt)
        assertFirstCheckInHistory()

        // 체크인과 동일한 시각은 거절하고, 이후 시각부터 재입장을 허용한다.
        verifyAccess().andExpect(status().isOk).andExpect(jsonPath("$.data.reasonCode").value("OUTSIDE_ALLOWED_TIME"))
        testClock.set(checkedInAt.plusSeconds(1).atZone(SettableClockConfig.KST))
        verifyAccess()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.result").value("ALLOW"))
            .andExpect(jsonPath("$.data.firstCheckIn").value(false))
        assertFirstCheckInHistory()
        assertThat(
            count(
                """
                SELECT COUNT(*)
                FROM door_access_log
                WHERE reservation_id = ?
                  AND result = 'ALLOW'
                """.trimIndent(),
                reservationId,
            ),
        ).isEqualTo(2)

        // TODO(step-5): DoorAccessCheckOutIntegrationTest를 복원해 체크아웃 후 토큰 폐기·재사용 차단을 재검증한다.
    }

    @Test
    @DisplayName("폐기된 세션의 서명이 유효한 JWT는 출입을 401로 차단하며 DB를 변경하지 않는다")
    fun revokedSessionCannotVerifyAccess() {
        authSessionService.revokeAllForMember(memberId, now())
        assertThat(jwtProvider.payloadOrNull(accessToken)).isNotNull()
        assertAuthenticationRejected()
    }

    @Test
    @DisplayName("세션 만료 시각과 같으면 아직 만료되지 않은 JWT도 출입을 401로 차단한다")
    fun expiredSessionCannotVerifyAccess() {
        jdbcTemplate.update("UPDATE auth_session SET expires_at = ? WHERE member_id = ?", now(), memberId)
        assertThat(jwtProvider.payloadOrNull(accessToken)).isNotNull()
        assertAuthenticationRejected()
    }

    @Test
    @DisplayName("유효한 서명이라도 JWT 회원과 sid 세션 소유자가 다르면 출입을 차단한다")
    fun sessionOwnerMismatchCannotVerifyAccess() {
        val otherMemberId = createMember(0)
        val payload = checkNotNull(jwtProvider.payloadOrNull(accessToken)) { "테스트 JWT의 서명과 유효기간을 확인할 수 있어야 한다." }
        val sessionId = checkNotNull(payload["sid"] as? String) { "테스트 JWT에는 문자열 sid가 있어야 한다." }
        accessToken = jwtProvider.genAccessToken(memberRepository.findById(otherMemberId).orElseThrow(), sessionId, now().plusHours(1))
        assertAuthenticationRejected()
        assertThat(count("SELECT COUNT(*) FROM door_access_log WHERE actor_member_id = ?", otherMemberId)).isZero()
    }

    @Test
    @DisplayName("인증된 다른 회원은 출입 토큰 발급·폐기·로그 조회에서 403을 받고 기존 토큰은 유지된다")
    fun nonOwnerCannotManageDoorAccess() {
        val originalToken = jdbcTemplate.queryForMap("SELECT id, token_hash FROM door_access_token WHERE reservation_id = ?", reservationId)
        val otherMemberId = createMember(0)
        val otherJwt = issueSessionBoundAccessToken(memberRepository.findById(otherMemberId).orElseThrow(), jwtProvider)
        val authorization = "Bearer $otherJwt"
        listOf(
            post("/api/v1/reservations/$reservationId/door-token"),
            patch("/api/v1/reservations/$reservationId/access-token/revoke"),
            get("/api/v1/reservations/$reservationId/access-logs"),
        ).forEach { request ->
            mockMvc
                .perform(request.header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("FORBIDDEN_NOT_OWNER"))
        }
        assertNoCheckInOrLogs()
        assertThat(
            count(
                """
                SELECT COUNT(*)
                FROM door_access_token
                WHERE reservation_id = ?
                  AND revoked_at IS NULL
                """.trimIndent(),
                reservationId,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForMap("SELECT id, token_hash FROM door_access_token WHERE reservation_id = ?", reservationId),
        ).isEqualTo(originalToken)
    }

    @Test
    @DisplayName("다른 회원의 출입 토큰 제출은 MEMBER_MISMATCH로 기록하고 체크인하지 않는다")
    fun otherMembersDoorTokenIsDenied() {
        val otherMemberId = createMember(0)
        accessToken = issueSessionBoundAccessToken(memberRepository.findById(otherMemberId).orElseThrow(), jwtProvider)
        verifyAccess().andExpect(status().isOk).andExpect(jsonPath("$.data.reasonCode").value("MEMBER_MISMATCH"))
        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED")
        assertThat(
            count(
                """
                SELECT COUNT(*)
                FROM door_access_log
                WHERE actor_member_id = ?
                  AND reservation_id = ?
                  AND result = 'DENY'
                  AND reason_code = 'MEMBER_MISMATCH'
                """.trimIndent(),
                otherMemberId,
                reservationId,
            ),
        ).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM reservation_status_history WHERE reservation_id = ?", reservationId)).isZero()
    }

    @Test
    @DisplayName("미인증 출입 요청은 401이며 출입 로그·예약 상태에 영향을 주지 않는다")
    fun anonymousCannotVerifyAccess() {
        mockMvc
            .perform(post("/api/v1/door-access/verify").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        assertNoCheckInOrLogs()
    }

    @ParameterizedTest
    @CsvSource("900, ALLOW", "901, DENY")
    @DisplayName("최초 체크인 15분 경계를 실제 인증·정책·DB 저장 경로에서도 유지한다")
    fun firstCheckInTimeBoundary(
        secondsAfterStart: Long,
        expectedResult: String,
    ) {
        testClock.set(now().plusSeconds(secondsAfterStart).atZone(SettableClockConfig.KST))
        // 출입 시간 판정을 검증하므로, 전진한 시각에서 유효한 JWT를 새로 발급한다.
        accessToken = issueSessionBoundAccessToken(memberRepository.findById(memberId).orElseThrow(), jwtProvider)
        val result = verifyAccess().andExpect(status().isOk).andExpect(jsonPath("$.data.result").value(expectedResult))
        if (expectedResult == "ALLOW") {
            assertThat(statusOf(reservationId)).isEqualTo("IN_USE")
            assertFirstCheckInHistory()
        } else {
            result.andExpect(jsonPath("$.data.reasonCode").value("OUTSIDE_ALLOWED_TIME"))
            assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED")
            assertThat(
                count(
                    """
                    SELECT COUNT(*)
                    FROM reservation
                    WHERE id = ?
                      AND checked_in_at IS NULL
                    """.trimIndent(),
                    reservationId,
                ),
            ).isEqualTo(1)
            assertThat(count("SELECT COUNT(*) FROM reservation_status_history WHERE reservation_id = ?", reservationId)).isZero()
            assertThat(
                count(
                    """
                    SELECT COUNT(*)
                    FROM door_access_log
                    WHERE reservation_id = ?
                      AND reason_code = 'OUTSIDE_ALLOWED_TIME'
                    """.trimIndent(),
                    reservationId,
                ),
            ).isEqualTo(1)
        }
        assertThat(
            count(
                """
                SELECT COUNT(*)
                FROM door_access_log
                WHERE reservation_id = ?
                  AND result = ?
                """.trimIndent(),
                reservationId,
                expectedResult,
            ),
        ).isEqualTo(1)
    }

    private fun verifyAccess(): ResultActions =
        mockMvc.perform(
            post("/api/v1/door-access/verify")
                .servletPath("/api/v1/door-access/verify")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()),
        )

    private fun requestBody(): String = objectMapper.writeValueAsString(mapOf("spaceId" to spaceId, "token" to doorToken))

    private fun assertAuthenticationRejected() {
        verifyAccess()
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_ACCESS_TOKEN.code))
            .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_ACCESS_TOKEN.message))
        assertNoCheckInOrLogs()
    }

    private fun assertNoCheckInOrLogs() {
        assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED")
        assertThat(
            count(
                """
                SELECT COUNT(*)
                FROM reservation
                WHERE id = ?
                  AND checked_in_at IS NULL
                """.trimIndent(),
                reservationId,
            ),
        ).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM reservation_status_history WHERE reservation_id = ?", reservationId)).isZero()
        assertThat(
            count(
                """
                SELECT COUNT(*)
                FROM door_access_log
                WHERE reservation_id = ?
                  OR actor_member_id = ?
                """.trimIndent(),
                reservationId,
                memberId,
            ),
        ).isZero()
    }

    private fun assertFirstCheckInHistory() {
        assertThat(count("SELECT COUNT(*) FROM reservation_status_history WHERE reservation_id = ?", reservationId)).isEqualTo(1)
        assertThat(
            count(
                """
                SELECT COUNT(*)
                FROM reservation_status_history
                WHERE reservation_id = ?
                  AND from_status = 'CONFIRMED'
                  AND to_status = 'IN_USE'
                  AND reason = 'FIRST_CHECK_IN'
                  AND changed_by_member_id = ?
                """.trimIndent(),
                reservationId,
                memberId,
            ),
        ).isEqualTo(1)
    }

    private fun count(
        sql: String,
        vararg parameters: Any,
    ): Int = requireNotNull(jdbcTemplate.queryForObject(sql, Int::class.javaObjectType, *parameters))
}
