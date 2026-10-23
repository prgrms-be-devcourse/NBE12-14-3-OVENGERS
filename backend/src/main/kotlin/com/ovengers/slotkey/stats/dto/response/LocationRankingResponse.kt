package com.ovengers.slotkey.stats.dto.response

import com.ovengers.slotkey.stats.entity.RankingMetric
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.RankingStatus
import java.time.LocalDate
import java.time.LocalDateTime

@JvmRecord
data class LocationRankingResponse(
    val period: RankingPeriodType,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val metric: RankingMetric,
    val status: RankingStatus,
    val confirmAt: LocalDateTime?,
    val computedAt: LocalDateTime?,
    val items: List<LocationRankingItem>,
)
