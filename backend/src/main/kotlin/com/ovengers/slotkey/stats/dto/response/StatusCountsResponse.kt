package com.ovengers.slotkey.stats.dto.response

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import java.time.LocalDate

/** 일곱 상태를 모두 0 포함으로 담는다. 실시간 값이며 랭킹 스냅샷이 아니다. */
@JvmRecord
data class StatusCountsResponse(
    val date: LocalDate,
    val counts: Map<ReservationStatus, Long>,
)
