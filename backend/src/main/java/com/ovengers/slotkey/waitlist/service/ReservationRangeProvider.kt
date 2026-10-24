package com.ovengers.slotkey.waitlist.service

import java.time.LocalDateTime

/** 슬롯 해제 이벤트의 reservationId로 해제된 공간·구간을 묻는 포트. */
interface ReservationRangeProvider {
    /** 예약 행이 없으면 null. */
    fun findRange(reservationId: Long?): ReservationRange?

    @JvmRecord
    data class ReservationRange(
        val spaceId: Long?,
        val startTime: LocalDateTime?,
        val endTime: LocalDateTime?,
    )
}
