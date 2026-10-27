package com.ovengers.slotkey.access.service

import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * "예약당 활성 출입 토큰은 1개"(CLAUDE.md 규칙 5)를 실제 DB 제약(door_access_token.active_reservation_id UNIQUE)으로 검증한다.
 * 같은 예약에 발급 요청이 동시에 들어와도 활성 토큰은 정확히 1개여야 하고,
 * 제약에 걸려 진 요청은 500이 아니라 409(RESERVATION_STATE_CONFLICT)로 끝나야 한다.
 */
class DoorAccessTokenConcurrencyTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var doorAccessTokenService: DoorAccessTokenService

    private var memberId = 0L
    private var reservationId = 0L

    @BeforeEach
    fun setUp() {
        memberId = createMember(0)
        val spaceId = createSpace()
        reservationId =
            createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, tomorrowAt(14, 0), tomorrowAt(15, 0))
    }

    @Test
    @DisplayName("토큰이 없는 예약에 발급 요청이 동시에 들어와도 활성 토큰은 정확히 1개이고, 진 요청은 409로 끝난다")
    fun issue_firstIssueConcurrently_leavesExactlyOneActiveToken() {
        val results: List<Throwable> = runConcurrently(issueTasks())

        assertThat(successCount(results)).isGreaterThanOrEqualTo(1)
        assertThat(failures(results)).isSubsetOf(ErrorCode.RESERVATION_STATE_CONFLICT)
        assertThat(activeTokenCount()).isEqualTo(1)
        // 성공한 발급 하나당 토큰이 정확히 하나 생긴다(실패한 요청은 롤백되어 흔적이 없다).
        assertThat(totalTokenCount()).isEqualTo(successCount(results))
    }

    @Test
    @DisplayName("이미 활성 토큰이 있는 예약에 재발급 요청이 동시에 들어와도 활성 토큰은 정확히 1개이고, 나머지는 REISSUED로 폐기돼 있다")
    fun issue_reissueConcurrently_leavesExactlyOneActiveToken() {
        doorAccessTokenService.issue(memberId, reservationId)

        val results: List<Throwable> = runConcurrently(issueTasks())

        assertThat(failures(results)).isSubsetOf(ErrorCode.RESERVATION_STATE_CONFLICT)
        assertThat(activeTokenCount()).isEqualTo(1)
        val total = totalTokenCount()
        assertThat(total).isEqualTo(1 + successCount(results))
        assertThat(revokedTokenCount("REISSUED")).isEqualTo(total - 1)
    }

    private fun issueTasks(): List<Runnable> =
        List(THREADS) {
            Runnable { doorAccessTokenService.issue(memberId, reservationId) }
        }

    // COUNT(*)의 결과는 기존 Java Integer 언박싱과 동일하게 null이면 실패한다.
    private fun activeTokenCount(): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM door_access_token
            WHERE reservation_id = ?
              AND revoked_at IS NULL
            """.trimIndent(),
            Int::class.javaObjectType,
            reservationId,
        )

    private fun totalTokenCount(): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM door_access_token
            WHERE reservation_id = ?
            """.trimIndent(),
            Int::class.javaObjectType,
            reservationId,
        )

    private fun revokedTokenCount(reason: String): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM door_access_token
            WHERE reservation_id = ?
              AND revoked_at IS NOT NULL
              AND revoke_reason = ?
            """.trimIndent(),
            Int::class.javaObjectType,
            reservationId,
            reason,
        )

    private companion object {
        private const val THREADS = 8
    }
}
