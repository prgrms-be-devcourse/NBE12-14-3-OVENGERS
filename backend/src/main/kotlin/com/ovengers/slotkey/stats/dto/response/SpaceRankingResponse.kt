package com.ovengers.slotkey.stats.dto.response

import com.ovengers.slotkey.stats.entity.RankingMetric
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.RankingStatus
import java.time.LocalDate
import java.time.LocalDateTime

/** periodStart는 포함, periodEnd는 제외(다음 기간 시작). confirmAt은 PENDING일 때만, computedAt은 확정 행이 있을 때만 값이 있다. */
@JvmRecord
data class SpaceRankingResponse(
    val period: RankingPeriodType,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val metric: RankingMetric,
    val status: RankingStatus,
    val confirmAt: LocalDateTime?,
    val computedAt: LocalDateTime?,
    val items: List<SpaceRankingItem>,
)
