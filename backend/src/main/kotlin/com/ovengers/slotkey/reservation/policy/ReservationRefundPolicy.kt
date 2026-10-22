package com.ovengers.slotkey.reservation.policy

import java.time.LocalDateTime

/**
 * 취소 시 환불 등급(§9, 기획서 "예약 취소" 2026-09-15 갱신).
 * 시작 1시간 전까지 취소는 전액(100%) 환급, 1시간 전~시작 전 취소는 50%만 환급한다.
 * 두 등급 모두 크레딧 원장에는 REFUND(+전액)와 PENALTY(-위약금)를 두 줄로 분리해
 * 기록한다 — 전액 환불이면 위약금이 0이므로 PENALTY 줄은 생략한다(서비스 레이어 책임).
 */
object ReservationRefundPolicy {
    private const val FULL_REFUND_HOURS_BEFORE_START = 1L
    private const val PENALTY_PERCENTAGE = 50

    /**
     * @param now         취소 처리 시각
     * @param startTime   예약 시작 시각(now보다 이후임이 호출부의 조건부 UPDATE로 이미 보장됨)
     * @param totalAmount 예약 총액(전액 환급 기준액, 100원 단위이므로 50% 계산에 반올림 오차가 없다)
     * @return 위약금(0이면 전액 환급 대상)
     */
    @JvmStatic
    fun calculatePenaltyAmount(
        now: LocalDateTime,
        startTime: LocalDateTime,
        totalAmount: Int,
    ): Int {
        val fullRefundDeadline = startTime.minusHours(FULL_REFUND_HOURS_BEFORE_START)
        if (!now.isAfter(fullRefundDeadline)) {
            return 0
        }
        return totalAmount * PENALTY_PERCENTAGE / 100
    }
}
