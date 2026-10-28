package com.ovengers.slotkey.waitlist.service

import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime
import java.util.ArrayList

/**
 * 같은 구간을 동시에 신청해도 활성 대기가 1건만 남는지 실제 MySQL로 검증한다.
 *
 * 이 경로에는 조건부 UPDATE도, 비관적 락도 없다. 판정자는 `UNIQUE(member_id, space_id,
 * start_time, end_time, active)` 하나이며, UNIQUE의 마지막 칸이 NULL 허용 컬럼이라
 * "MySQL이 UNIQUE에서 NULL을 서로 다른 값으로 본다"는 성질에 기대고 있다. 그래서 글로 적은 보장이
 * 아니라 실측이 필요하다(step-1-review.md M1).
 *
 * 테스트하지 않는 것: 활성 3건 제한의 동시 초과. 제한 검사는 애플리케이션 검사이고
 * docs/plans/waitlist-outbox/01-waitlist-rules.md §1이 "동시 요청 시 초과를 수용한다"로 정했다.
 * 대기 신청은 돈도 슬롯도 움직이지 않으므로 초과 1~2건이 남는 것이 사용자에게 손해가 아니다.
 */
class WaitlistApplyConcurrencyTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var waitlistService: WaitlistService

    private var memberId: Long = 0L
    private var spaceId: Long = 0L
    private lateinit var startTime: LocalDateTime
    private lateinit var endTime: LocalDateTime

    @BeforeEach
    fun setUp() {
        memberId = createMember(BALANCE)
        val occupierId = createMember(BALANCE)
        spaceId = createSpace()
        startTime = tomorrowAt(14, 0)
        endTime = tomorrowAt(15, 0)
        createReservation(occupierId, spaceId, ReservationStatus.CONFIRMED, startTime, endTime)
    }

    @Test
    @DisplayName("같은 회원이 같은 구간을 동시에 신청하면 한 건만 성공하고 나머지는 WAITLIST_DUPLICATE다")
    fun apply_sameRangeConcurrently_keepsSingleActiveEntry() {
        val tasks = ArrayList<Runnable>()
        for (i in 0 until THREADS) {
            tasks.add(Runnable { waitlistService.apply(memberId, spaceId, startTime, endTime) })
        }

        val results = runConcurrently(tasks)

        assertThat(successCount(results)).isEqualTo(1)
        assertThat(failures(results))
            .hasSize(THREADS - 1)
            .containsOnly(ErrorCode.WAITLIST_DUPLICATE)
        assertThat(countWaitlistRows("WAITING")).isEqualTo(1)
        assertThat(countActiveWaitlistRows()).isEqualTo(1)
        assertThat(waitlistService.getMyEntries(memberId)).hasSize(1)
    }

    private fun countWaitlistRows(status: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM waitlist_entry WHERE member_id = ? AND status = ?",
            Int::class.javaObjectType,
            memberId,
            status,
        )

    private fun countActiveWaitlistRows(): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM waitlist_entry WHERE member_id = ? AND active IS NOT NULL",
            Int::class.javaObjectType,
            memberId,
        )

    private companion object {
        private const val BALANCE = 100_000
        private const val THREADS = 5
    }
}
