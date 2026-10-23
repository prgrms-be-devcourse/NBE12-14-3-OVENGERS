package com.ovengers.slotkey.stats.entity

/**
 * 랭킹 응답 상태.
 * CONFIRMED: 끝난 기간의 확정값. PENDING: 아직 끝나지 않은 기간(순위 없음). UNAVAILABLE: 확정 기록이 시작되기 전의 기간.
 */
enum class RankingStatus {
    CONFIRMED,
    PENDING,
    UNAVAILABLE,
}
