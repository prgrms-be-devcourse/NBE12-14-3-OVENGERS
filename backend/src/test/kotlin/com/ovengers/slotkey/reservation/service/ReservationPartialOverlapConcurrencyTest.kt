package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

/**
 * 시간대가 "일부만" 겹치는 HOLD 두 건이 동시에 들어오는 경우를 실제 DB로 검증한다(core-domain-decisions 4-1, 4-2).
 * 기존 ReservationConcurrencyTest는 "완전히 같은 시간대"만 다룬다. 여기서는 UNIQUE(space_id, slot_start)가
 * 겹치는 슬롯 하나만으로도 전체를 막아 주는지, 그리고 진 요청이 데드락 등으로 500이 되지 않는지를 확인한다.
 * 진 요청의 예약·슬롯은 롤백되어 흔적이 남지 않아야 한다.
 */
class ReservationPartialOverlapConcurrencyTest : ReservationIntegrationTestSupport() {
    companion object {
        private val ROUNDS: Int = 10
    }

    @Autowired
    private lateinit var reservationHoldService: ReservationHoldService

    @Test
    @DisplayName("14:00~15:00과 14:30~15:30(뒤 30분만 겹침)이 동시에 들어오면 정확히 1건만 성공한다")
    fun createHold_partialOverlap_onlyOneSucceeds() {
        for (round in 0 until ROUNDS) {
            assertOnlyOneWins(round, tomorrowAt(14, 0), tomorrowAt(15, 0), tomorrowAt(14, 30), tomorrowAt(15, 30))
        }
    }

    @Test
    @DisplayName("14:00~16:00 안에 14:30~15:00이 포함되는 요청이 동시에 들어오면 정확히 1건만 성공한다")
    fun createHold_containedRange_onlyOneSucceeds() {
        for (round in 0 until ROUNDS) {
            assertOnlyOneWins(round, tomorrowAt(14, 0), tomorrowAt(16, 0), tomorrowAt(14, 30), tomorrowAt(15, 0))
        }
    }

    private fun assertOnlyOneWins(
        round: Int,
        startA: LocalDateTime,
        endA: LocalDateTime,
        startB: LocalDateTime,
        endB: LocalDateTime,
    ) {
        // 판마다 공간·회원을 새로 만들어 이전 판의 슬롯과 섞이지 않게 한다.
        val spaceId: Long = createSpace()
        val memberA: Long = createMember(0)
        val memberB: Long = createMember(0)

        val results: List<Throwable> =
            runConcurrently(
                listOf(
                    Runnable { reservationHoldService.createHold(memberA, spaceId, startA, endA) },
                    Runnable { reservationHoldService.createHold(memberB, spaceId, startB, endB) },
                ),
            )

        assertThat(successCount(results)).`as`("round %d: 성공 수", round).isEqualTo(1)
        // 데드락/락 대기 초과 등 DB 예외가 그대로 새면 ErrorCode가 아니라 예외 자체가 담겨 여기서 실패한다.
        assertThat(failures(results))
            .`as`("round %d: 실패 사유", round)
            .containsExactly(ErrorCode.RESERVATION_SLOT_CONFLICT)

        // 진 요청의 예약 행은 롤백되어 남지 않는다.
        val reservationIds: List<Long> =
            jdbcTemplate.queryForList(
                "SELECT id FROM reservation WHERE space_id = ?",
                Long::class.javaObjectType,
                spaceId,
            )
        assertThat(reservationIds).`as`("round %d: 남은 예약", round).hasSize(1)

        // 슬롯은 이긴 예약 몫만 남고, 전부 그 예약에 묶여 있다.
        val winnerId: Long = reservationIds.get(0)
        val expectedSlots: Int = winnerSlotCount(winnerId, spaceId)
        assertThat(countSlots(winnerId)).`as`("round %d: 이긴 예약의 슬롯", round).isEqualTo(expectedSlots)
        assertThat(countSlotsInRange(spaceId, tomorrowAt(0, 0), tomorrowAt(23, 30).plusMinutes(30)))
            .`as`("round %d: 공간 전체 슬롯", round)
            .isEqualTo(expectedSlots)
    }

    /** 이긴 예약의 (end - start)를 30분 슬롯 수로 환산한다. */

    private fun winnerSlotCount(
        reservationId: Long,
        spaceId: Long,
    ): Int {
        val minutes: Int? =
            jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(MINUTE, start_time, end_time) FROM reservation WHERE id = ? AND space_id = ?",
                Int::class.javaObjectType,
                reservationId,
                spaceId,
            )
        return minutes!! / 30
    }
}
