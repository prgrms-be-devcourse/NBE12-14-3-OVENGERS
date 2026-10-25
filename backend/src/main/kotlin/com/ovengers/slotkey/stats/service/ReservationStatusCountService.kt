package com.ovengers.slotkey.stats.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.stats.dto.response.StatusCountsResponse
import com.ovengers.slotkey.stats.repository.ReservationStatusCountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.EnumMap

/** 운영 대시보드의 "날짜별 상태별 예약 건수". 브라우저가 예약을 내려받아 세던 방식을 서버 집계로 옮긴 것이다. */
@Service
@Transactional(readOnly = true)
class ReservationStatusCountService(
    private val repository: ReservationStatusCountRepository,
    private val clock: Clock,
) {
    /** @param date 생략하면 오늘(KST). @param spaceId 생략하면 전체 공간 */
    fun count(
        date: LocalDate?,
        spaceId: Long?,
    ): StatusCountsResponse {
        if (spaceId != null && spaceId < 1) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "spaceId는 양수여야 합니다.")
        }
        val target = date ?: LocalDate.now(clock)
        val found =
            repository.countByStatus(target.atStartOfDay(), target.plusDays(1).atStartOfDay(), spaceId)

        val counts = EnumMap<ReservationStatus, Long>(ReservationStatus::class.java)
        for (status in ReservationStatus.entries) {
            counts[status] = found[status] ?: 0L
        }
        return StatusCountsResponse(target, counts)
    }
}
