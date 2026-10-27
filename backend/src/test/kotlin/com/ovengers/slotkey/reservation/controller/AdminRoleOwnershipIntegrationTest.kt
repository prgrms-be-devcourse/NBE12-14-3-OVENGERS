package com.ovengers.slotkey.reservation.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.credit.service.CreditService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@AutoConfigureMockMvc
@Import(SettableClockConfig::class)
class AdminRoleOwnershipIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var testClock: SettableClock

    @Autowired
    private lateinit var creditService: CreditService

    private var ownerId = 0L
    private var adminId = 0L
    private var reservationId = 0L
    private var spaceId = 0L
    private lateinit var ownerJwt: String
    private lateinit var adminJwt: String
    private lateinit var endTime: LocalDateTime

    @BeforeEach
    fun setUp() {
        testClock.set(LocalDateTime.of(2026, 10, 26, 14, 0).atZone(SettableClockConfig.KST))
        ownerId = createMember(30000)
        adminId = createMember(0)
        jdbcTemplate.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", adminId)
        ownerJwt = issueSessionBoundAccessToken(memberRepository.findById(ownerId).orElseThrow(), jwtProvider)
        adminJwt = issueSessionBoundAccessToken(memberRepository.findById(adminId).orElseThrow(), jwtProvider)
        assertThat(jwtProvider.payloadOrNull(adminJwt)).containsEntry("role", "ADMIN")
        assertThat(adminId).isNotEqualTo(ownerId)
        spaceId = createSpace()
    }

    @ParameterizedTest(name = "ADMIN: {0} ({1}) -> 403 FORBIDDEN_NOT_OWNER")
    @CsvSource(
        "cancel, CONFIRMED",
        "extend, CONFIRMED",
        "pay, HELD",
        "check-out, IN_USE",
        "door-token, CONFIRMED",
        "access-token/revoke, CONFIRMED",
        "access-logs, CONFIRMED",
    )
    @DisplayName("ADMIN 역할도 타인 예약의 사용자 전용 API 7개에 접근하면 부작용 없이 거절된다")
    fun adminCannotActAsReservationOwner(
        endpoint: String,
        initialStatus: ReservationStatus,
    ) {
        prepareReservation(initialStatus)
        val before = snapshot()
        val path = "/api/v1/reservations/$reservationId/$endpoint"
        val request =
            when (endpoint) {
                "access-token/revoke" -> patch(path)
                "access-logs" -> get(path)
                "extend" ->
                    post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(mapOf("expectedEndTime" to endTime, "newEndTime" to endTime.plusMinutes(30))),
                        )
                "pay" -> {
                    val spaceVersion =
                        jdbcTemplate.queryForObject("SELECT version FROM spaces WHERE id = ?", Int::class.javaObjectType, spaceId)
                    post(path)
                        .header("Idempotency-Key", "admin-owner-pay-$reservationId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("spaceVersion" to spaceVersion)))
                }
                else -> post(path)
            }

        mockMvc
            .perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer $adminJwt"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN_NOT_OWNER"))

        assertThat(statusOf(reservationId)).isEqualTo(initialStatus.name)
        assertThat(countSlots(reservationId)).isEqualTo(2)
        assertThat(snapshot()).describedAs("%s 거절 후 DB 상태", endpoint).isEqualTo(before)
        assertThat(countIdempotencyKeys(adminId)).isZero()
        assertThat(countIdempotencyKeys(ownerId)).isZero()
        assertThat(auditLogs()).isEmpty()
    }

    @Test
    @DisplayName("대조군: 소유자 세션 JWT로 시작 전 CONFIRMED 예약을 취소할 수 있다")
    fun ownerCanCancelOwnReservation() {
        prepareReservation(ReservationStatus.CONFIRMED)

        mockMvc
            .perform(post("/api/v1/reservations/$reservationId/cancel").header(HttpHeaders.AUTHORIZATION, "Bearer $ownerJwt"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("CANCELLED"))

        assertCancelledAndRefunded()
        assertThat(auditLogs()).isEmpty()
    }

    @Test
    @DisplayName("대조군: ADMIN 세션 JWT로 관리자 전용 경로의 타인 예약 강제 취소는 성공한다")
    fun adminCanForceCancelThroughAdminEndpoint() {
        prepareReservation(ReservationStatus.CONFIRMED)

        mockMvc
            .perform(
                post("/api/v1/admin/reservations/$reservationId/force-cancel")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $adminJwt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"rule 4 ownership control"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("CANCELLED"))

        assertCancelledAndRefunded()
        val audit = auditLogs()
        assertThat(audit).hasSize(1)
        assertThat(audit.single()).containsEntry("actor_member_id", adminId).containsEntry("action", "FORCE_CANCEL_RESERVATION")
    }

    private fun prepareReservation(initialStatus: ReservationStatus) {
        val startTime = if (initialStatus == ReservationStatus.IN_USE) now() else now().plusHours(1)
        endTime = startTime.plusHours(1)
        val storedStatus = if (initialStatus == ReservationStatus.IN_USE) ReservationStatus.CONFIRMED else initialStatus
        reservationId = createReservation(ownerId, spaceId, storedStatus, startTime, endTime)
        if (initialStatus == ReservationStatus.IN_USE) {
            jdbcTemplate.update("UPDATE reservation SET status = 'IN_USE', checked_in_at = ? WHERE id = ?", now(), reservationId)
        }
        if (initialStatus != ReservationStatus.HELD) {
            // 확정된 예약은 실제 차감 원장과 활성 토큰을 준비해 환불·재발급·폐기 부작용도 잡는다.
            creditService.charge(ownerId, reservationId, PRICE_PER_SLOT * 2)
            mockMvc
                .perform(post("/api/v1/reservations/$reservationId/door-token").header(HttpHeaders.AUTHORIZATION, "Bearer $ownerJwt"))
                .andExpect(status().isCreated)
            assertThat(activeTokens()).hasSize(1)
            assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isEqualTo(1)
        } else {
            assertThat(activeTokens()).isEmpty()
            assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isZero()
        }
        assertThat(statusOf(reservationId)).isEqualTo(initialStatus.name)
        assertThat(countSlots(reservationId)).isEqualTo(2)
        assertThat(countIdempotencyKeys(adminId)).isZero()
        assertThat(countIdempotencyKeys(ownerId)).isZero()
        assertThat(auditLogs()).isEmpty()
    }

    private fun snapshot(): Map<String, Any> =
        mapOf(
            "reservation" to jdbcTemplate.queryForMap("SELECT * FROM reservation WHERE id = ?", reservationId),
            "slots" to jdbcTemplate.queryForList("SELECT * FROM reservation_slot WHERE reservation_id = ? ORDER BY id", reservationId),
            "ownerBalance" to balanceOf(ownerId),
            "adminBalance" to balanceOf(adminId),
            "ledger" to
                jdbcTemplate.queryForList(
                    "SELECT * FROM credit_transaction WHERE member_id IN (?, ?) OR reservation_id = ? ORDER BY id",
                    ownerId,
                    adminId,
                    reservationId,
                ),
            "activeTokens" to activeTokens(),
            "tokens" to jdbcTemplate.queryForList("SELECT * FROM door_access_token WHERE reservation_id = ? ORDER BY id", reservationId),
            "history" to
                jdbcTemplate.queryForList("SELECT * FROM reservation_status_history WHERE reservation_id = ? ORDER BY id", reservationId),
            "accessLogs" to jdbcTemplate.queryForList("SELECT * FROM door_access_log WHERE reservation_id = ? ORDER BY id", reservationId),
            "idempotencyKeys" to
                jdbcTemplate.queryForList("SELECT * FROM idempotency_key WHERE member_id IN (?, ?) ORDER BY id", ownerId, adminId),
            "auditLogs" to auditLogs(),
        )

    private fun activeTokens(): List<Map<String, Any>> =
        jdbcTemplate.queryForList(
            "SELECT * FROM door_access_token WHERE reservation_id = ? AND revoked_at IS NULL ORDER BY id",
            reservationId,
        )

    private fun auditLogs(): List<Map<String, Any>> =
        jdbcTemplate.queryForList(
            "SELECT * FROM audit_logs WHERE actor_member_id IN (?, ?) OR (target_type = 'RESERVATION' AND target_id = ?) ORDER BY id",
            ownerId,
            adminId,
            reservationId,
        )

    private fun assertCancelledAndRefunded() {
        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED")
        assertThat(countSlots(reservationId)).isZero()
        assertThat(balanceOf(ownerId)).isEqualTo(30000)
        assertThat(balanceOf(adminId)).isZero()
        assertThat(countLedger(reservationId, "REFUND")).isEqualTo(1)
        assertThat(sumLedger(reservationId, "REFUND")).isEqualTo(PRICE_PER_SLOT * 2)
        assertThat(activeTokens()).isEmpty()
    }
}
