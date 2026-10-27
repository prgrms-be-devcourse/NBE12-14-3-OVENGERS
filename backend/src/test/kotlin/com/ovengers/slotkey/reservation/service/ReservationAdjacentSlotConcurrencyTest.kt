package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 끝 시각과 시작 시각이 정확히 맞닿는 두 HOLD(14:00~15:00, 15:00~16:00)가 동시에 들어오는 경우를 검증한다.
 * 슬롯은 [start, end) 반열린 구간으로 저장되므로 15:00 슬롯은 두 번째 예약의 것이고, 둘은 충돌하지 않아야 한다.
 * off-by-one으로 첫 예약이 15:00 슬롯까지 잡거나(불필요한 충돌), 슬롯이 하나 모자라면 여기서 실패한다.
 */
class ReservationAdjacentSlotConcurrencyTest : ReservationIntegrationTestSupport() {
    companion object {
        private val ROUNDS: Int = 10
    }

    @Autowired
    private lateinit var reservationHoldService: ReservationHoldService

    @Test
    @DisplayName("맞닿은 시간대 두 HOLD가 동시에 들어와도 둘 다 성공하고, 각자 자기 슬롯 2개만 가진다")
    fun createHold_adjacentRanges_bothSucceed() {
        for (round in 0 until ROUNDS) {
            val spaceId: Long = createSpace()
            val memberA: Long = createMember(0)
            val memberB: Long = createMember(0)

            val results: List<Throwable> =
                runConcurrently(
                    listOf(
                        Runnable { reservationHoldService.createHold(memberA, spaceId, tomorrowAt(14, 0), tomorrowAt(15, 0)) },
                        Runnable { reservationHoldService.createHold(memberB, spaceId, tomorrowAt(15, 0), tomorrowAt(16, 0)) },
                    ),
                )

            assertThat(failures(results)).`as`("round %d: 실패", round).isEmpty()
            assertThat(successCount(results)).`as`("round %d: 성공 수", round).isEqualTo(2)

            val reservationIds: List<Long> =
                jdbcTemplate.queryForList(
                    "SELECT id FROM reservation WHERE space_id = ?",
                    Long::class.javaObjectType,
                    spaceId,
                )
            assertThat(reservationIds).`as`("round %d: 예약 수", round).hasSize(2)
            for (reservationId in reservationIds) {
                assertThat(countSlots(reservationId))
                    .`as`("round %d: 예약 %d의 슬롯", round, reservationId)
                    .isEqualTo(2)
            }

            // 14:00, 14:30, 15:00, 15:30 — 정확히 4개, 경계 밖(13:30 이전 / 16:00 이후)에는 없다.
            assertThat(countSlotsInRange(spaceId, tomorrowAt(14, 0), tomorrowAt(16, 0))).isEqualTo(4)
            assertThat(countSlotsInRange(spaceId, tomorrowAt(9, 0), tomorrowAt(14, 0))).isZero()
            assertThat(countSlotsInRange(spaceId, tomorrowAt(16, 0), tomorrowAt(22, 0))).isZero()
        }
    }
}
