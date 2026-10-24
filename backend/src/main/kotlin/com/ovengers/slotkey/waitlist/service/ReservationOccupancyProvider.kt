package com.ovengers.slotkey.waitlist.service

import java.time.LocalDateTime

/** 대기 신청이 예약 도메인에 묻는 점유 여부 포트. */
interface ReservationOccupancyProvider {
    /** [startInclusive, endExclusive) 안에 유효 점유 슬롯이 하나라도 있는가. */
    fun hasOccupiedSlot(
        spaceId: Long?,
        startInclusive: LocalDateTime?,
        endExclusive: LocalDateTime?,
        now: LocalDateTime?,
    ): Boolean

    /** 같은 회원의 유효 점유 예약이 같은 공간에서 이 구간과 겹치는가. */
    fun hasOwnOccupyingReservationOverlapping(
        memberId: Long?,
        spaceId: Long?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        now: LocalDateTime?,
    ): Boolean
}
