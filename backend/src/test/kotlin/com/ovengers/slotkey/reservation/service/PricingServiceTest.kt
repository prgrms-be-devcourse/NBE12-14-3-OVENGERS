package com.ovengers.slotkey.reservation.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class PricingServiceTest {
    private val pricingService: PricingService = PricingService()

    @Test
    @DisplayName("2시간 구간은 30분 슬롯 4개로 계산된다")
    fun calculateSlotCount_twoHours_returnsFourSlots() {
        val start: LocalDateTime = LocalDateTime.of(2026, 9, 17, 14, 0)
        val end: LocalDateTime = LocalDateTime.of(2026, 9, 17, 16, 0)

        val slotCount: Int = pricingService.calculateSlotCount(start, end)

        assertThat(slotCount).isEqualTo(4)
    }

    @Test
    @DisplayName("30분 구간은 슬롯 1개로 계산된다")
    fun calculateSlotCount_thirtyMinutes_returnsOneSlot() {
        val start: LocalDateTime = LocalDateTime.of(2026, 9, 17, 14, 0)
        val end: LocalDateTime = LocalDateTime.of(2026, 9, 17, 14, 30)

        val slotCount: Int = pricingService.calculateSlotCount(start, end)

        assertThat(slotCount).isEqualTo(1)
    }

    @Test
    @DisplayName("30분 배수가 아닌 구간은 남는 시간을 버리고 내림 계산한다")
    fun calculateSlotCount_notSlotAligned_flooredDown() {
        val start: LocalDateTime = LocalDateTime.of(2026, 9, 17, 14, 0)
        val end: LocalDateTime = LocalDateTime.of(2026, 9, 17, 14, 45)

        val slotCount: Int = pricingService.calculateSlotCount(start, end)

        assertThat(slotCount).isEqualTo(1)
    }

    @Test
    @DisplayName("슬롯 단가와 슬롯 수를 곱해 총액을 계산한다")
    fun calculateTotalAmount_multipliesPriceAndSlotCount() {
        val totalAmount: Int = pricingService.calculateTotalAmount(5000, 4)

        assertThat(totalAmount).isEqualTo(20000)
    }

    @Test
    @DisplayName("슬롯 수가 0이면 총액도 0이다")
    fun calculateTotalAmount_zeroSlots_returnsZero() {
        val totalAmount: Int = pricingService.calculateTotalAmount(5000, 0)

        assertThat(totalAmount).isEqualTo(0)
    }
}
