package com.ovengers.slotkey.reservation.service

import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime

private const val PRICING_SLOT_MINUTES = 30L

/** price_per_slot_snapshot × 점유 슬롯 수로 예약 금액을 계산한다. */
@Service
class PricingService {
    fun calculateSlotCount(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Int = (Duration.between(start, end).toMinutes() / PRICING_SLOT_MINUTES).toInt()

    fun calculateTotalAmount(
        pricePerSlotSnapshot: Int,
        slotCount: Int,
    ): Int = pricePerSlotSnapshot * slotCount
}
