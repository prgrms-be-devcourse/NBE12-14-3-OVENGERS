package com.ovengers.slotkey.stats.dto.response

import com.ovengers.slotkey.stats.entity.RankingMetric
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.RankingStatus
import java.time.LocalDate
import java.time.LocalDateTime

/** 일반 회원용 지역 랭킹. 관리자용 [LocationRankingResponse]에서 순매출만 뺀 모양이다. */
@JvmRecord
data class PublicLocationRankingResponse(
    val period: RankingPeriodType,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val metric: RankingMetric,
    val status: RankingStatus,
    val confirmAt: LocalDateTime?,
    val computedAt: LocalDateTime?,
    val items: List<Item>,
) {
    @JvmRecord
    data class Item(
        val rank: Int,
        val previousRank: Int?,
        val location: String,
        val spaceCount: Int,
        val reservationCount: Int,
        val slotCount: Int,
    )

    companion object {
        @JvmStatic
        fun from(source: LocationRankingResponse): PublicLocationRankingResponse {
            val items =
                source.items
                    .stream()
                    .map { i ->
                        Item(
                            i.rank,
                            i.previousRank,
                            i.location,
                            i.spaceCount,
                            i.reservationCount,
                            i.slotCount,
                        )
                    }.toList()
            return PublicLocationRankingResponse(
                source.period,
                source.periodStart,
                source.periodEnd,
                source.metric,
                source.status,
                source.confirmAt,
                source.computedAt,
                items,
            )
        }
    }
}
