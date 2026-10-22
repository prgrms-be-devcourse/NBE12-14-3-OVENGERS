package com.ovengers.slotkey.reservation.entity

/**
 * 예약 상태 7종 (docs/core-domain-decisions.md core-domain-decisions 3-2).
 *
 * <pre>
 * HELD --(결제 성공)--> CONFIRMED --(최초 체크인)--> IN_USE --(체크아웃/종료시각)--> COMPLETED
 *   |                       |
 *   +--(10분 경과)--> EXPIRED +--(취소: now < start_time)--> CANCELLED
 *                           +--(start + 15분 미체크인)--> NO_SHOW
 * </pre>
 */
enum class ReservationStatus {
    HELD,
    EXPIRED,
    CONFIRMED,
    IN_USE,
    COMPLETED,
    CANCELLED,
    NO_SHOW,
}
