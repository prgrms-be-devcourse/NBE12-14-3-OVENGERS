package com.ovengers.slotkey.waitlist.entity

/**
 * 대기 신청 상태(docs/plans/waitlist-outbox/01-waitlist-rules.md).
 * WAITING만 활성이며, 본인 취소는 CANCELLED, 구간이 지난 뒤의 정리는 EXPIRED다.
 * (EXPIRED 전이는 1단계 범위가 아니다 — 컬럼 값만 미리 정의해 둔다.)
 */
enum class WaitlistStatus {
    WAITING,
    CANCELLED,
    EXPIRED,
}
