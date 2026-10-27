package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime
import java.util.ArrayList

/**
 * 예약 연장(POST /reservations/{id}/extend)을 실제 DB로 검증한다(core-domain-decisions 4-3, 7).
 * 슬롯 삽입·크레딧 차감·종료 시각 UPDATE가 한 트랜잭션이라는 점(실패 시 전부 롤백)과 동시 요청 하의 정합성이 대상이다.
 * 기본 예약: 내일 14:00~15:00(슬롯 2개, 총액 10,000원, CONFIRMED). 연장 목표: 16:00.
 */
class ReservationExtendIntegrationTest : ReservationIntegrationTestSupport() {
    companion object {
        private val INITIAL_BALANCE: Int = 100_000
        private val ORIGINAL_TOTAL: Int = 10_000
        private val EXTENSION_COST: Int = 10_000
        private val THREADS: Int = 5
    }

    // 추가 슬롯 2개 × 5,000원

    @Autowired
    private lateinit var reservationExtendService: ReservationExtendService

    @Autowired
    private lateinit var reservationHoldService: ReservationHoldService

    @Autowired
    private lateinit var reservationCancelService: ReservationCancelService

    private var memberId: Long = 0L
    private var spaceId: Long = 0L
    private var reservationId: Long = 0L
    private lateinit var endTime: LocalDateTime
    private lateinit var newEndTime: LocalDateTime

    @BeforeEach
    fun setUp() {
        memberId = createMember(INITIAL_BALANCE)
        spaceId = createSpace()
        endTime = tomorrowAt(15, 0)
        newEndTime = tomorrowAt(16, 0)
        reservationId = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, tomorrowAt(14, 0), endTime)
    }

    @Test
    @DisplayName("연장에 성공하면 추가 슬롯이 생기고, 추가 금액만큼 차감되며, 종료 시각과 총액이 갱신된다")
    fun extend_success_updatesSlotsBalanceAndReservation() {
        val response: ReservationResponse = reservationExtendService.extend(memberId, reservationId, endTime, newEndTime)

        assertThat(response.endTime).isEqualTo(newEndTime)
        assertThat(response.totalAmount).isEqualTo(ORIGINAL_TOTAL + EXTENSION_COST)
        assertThat(countSlots(reservationId)).isEqualTo(4)
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE - EXTENSION_COST)
        assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isEqualTo(1)
        assertThat(sumLedger(reservationId, "RESERVATION_CHARGE")).isEqualTo(-EXTENSION_COST)
    }

    @Test
    @DisplayName("예약 뒤에 공간 가격이 올라도 연장 금액은 원 예약의 단가(price_per_slot_snapshot)로 계산한다")
    fun extend_afterSpacePriceIncrease_usesOriginalSnapshotPrice() {
        jdbcTemplate.update("UPDATE spaces SET price_per_slot = 9000, version = version + 1 WHERE id = ?", spaceId)

        reservationExtendService.extend(memberId, reservationId, endTime, newEndTime)

        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE - EXTENSION_COST)
    }

    @Test
    @DisplayName("추가 슬롯 중 일부가 이미 점유돼 있으면 409이고, 먼저 넣은 슬롯·크레딧 차감이 모두 롤백되어 원 예약이 그대로다")
    fun extend_partialSlotConflict_rollsBackEverything() {
        // 다른 회원이 15:30~16:00을 이미 확정해 둔 상태: 연장은 15:00 삽입에는 성공하고 15:30에서 충돌한다.
        val otherMemberId: Long = createMember(INITIAL_BALANCE)
        createReservation(otherMemberId, spaceId, ReservationStatus.CONFIRMED, tomorrowAt(15, 30), newEndTime)

        assertThatThrownBy({ reservationExtendService.extend(memberId, reservationId, endTime, newEndTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_SLOT_CONFLICT)

        assertThat(countSlots(reservationId)).isEqualTo(2)
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getEndTime()).isEqualTo(endTime)
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getTotalAmount())
            .isEqualTo(ORIGINAL_TOTAL)
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE)
        assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isZero()
    }

    @Test
    @DisplayName("잔액이 부족하면 422이고, 이미 넣은 추가 슬롯이 롤백되어 슬롯·종료 시각·잔액이 그대로다")
    fun extend_insufficientBalance_rollsBackSlots() {
        setBalance(memberId, EXTENSION_COST - 1_000)

        assertThatThrownBy({ reservationExtendService.extend(memberId, reservationId, endTime, newEndTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INSUFFICIENT_BALANCE)

        assertThat(countSlots(reservationId)).isEqualTo(2)
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getEndTime()).isEqualTo(endTime)
        assertThat(balanceOf(memberId)).isEqualTo(EXTENSION_COST - 1_000)
        assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isZero()
    }

    @Test
    @DisplayName("같은 예약에 같은 연장 요청이 동시에 들어와도 정확히 1건만 성공하고 슬롯·크레딧은 1회분만 반영된다")
    fun extend_sameRequestConcurrently_onlyOneSucceeds() {
        val tasks: MutableList<Runnable> = ArrayList()
        for (i in 0 until THREADS) {
            tasks.add(Runnable { reservationExtendService.extend(memberId, reservationId, endTime, newEndTime) })
        }

        val results: List<Throwable> = runConcurrently(tasks)

        // 진 요청은 슬롯 UNIQUE 충돌(RESERVATION_SLOT_CONFLICT) 또는, 이미 늘어난 종료 시각을 본 뒤의
        // 낙관적 검사 실패(RESERVATION_STATE_CONFLICT)로 끝난다.
        assertThat(successCount(results)).isEqualTo(1)
        assertThat(failures(results))
            .hasSize(THREADS - 1)
            .isSubsetOf(ErrorCode.RESERVATION_SLOT_CONFLICT, ErrorCode.RESERVATION_STATE_CONFLICT)

        assertThat(countSlots(reservationId)).isEqualTo(4)
        assertThat(balanceOf(memberId)).isEqualTo(INITIAL_BALANCE - EXTENSION_COST)
        assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isEqualTo(1)
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getEndTime()).isEqualTo(newEndTime)
    }

    @Test
    @DisplayName("연장과 같은 시간대의 신규 예약(HOLD)이 동시에 들어오면 하나만 성공하고 그 시간대 슬롯은 한 예약 몫만 남는다")
    fun extend_concurrentWithNewHold_onlyOneSucceeds() {
        val otherMemberId: Long = createMember(INITIAL_BALANCE)
        val tasks: List<Runnable> =
            listOf(
                Runnable { reservationExtendService.extend(memberId, reservationId, endTime, newEndTime) },
                Runnable { reservationHoldService.createHold(otherMemberId, spaceId, endTime, newEndTime) },
            )

        val results: List<Throwable> = runConcurrently(tasks)

        assertThat(successCount(results)).isEqualTo(1)
        assertThat(failures(results)).containsExactly(ErrorCode.RESERVATION_SLOT_CONFLICT)
        assertThat(countSlotsInRange(spaceId, endTime, newEndTime)).isEqualTo(2)
    }

    @Test
    @DisplayName("연장한 예약을 시작 1시간 전 이전에 취소하면 연장분까지 포함한 총액 전액이 환불된다")
    fun extendThenCancel_refundsTotalAmountIncludingExtension() {
        reservationExtendService.extend(memberId, reservationId, endTime, newEndTime)

        reservationCancelService.cancel(memberId, reservationId)

        assertThat(statusOf(reservationId)).isEqualTo("CANCELLED")
        assertThat(sumLedger(reservationId, "REFUND")).isEqualTo(ORIGINAL_TOTAL + EXTENSION_COST)
        assertThat(countLedger(reservationId, "PENALTY")).isZero()
        assertThat(countSlots(reservationId)).isZero()
        // 원 예약 결제분은 이 테스트가 만든 잔액에 반영돼 있지 않으므로: 초기 - 연장 차감 + 전액 환불
        assertThat(balanceOf(memberId))
            .isEqualTo(INITIAL_BALANCE - EXTENSION_COST + ORIGINAL_TOTAL + EXTENSION_COST)
    }
}
