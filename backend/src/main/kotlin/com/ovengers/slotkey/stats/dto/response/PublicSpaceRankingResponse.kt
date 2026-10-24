package com.ovengers.slotkey.stats.dto.response

import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.stats.entity.RankingMetric
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.RankingStatus
import java.time.LocalDate
import java.time.LocalDateTime

/** 일반 회원용 공간 랭킹. 관리자용 [SpaceRankingResponse]에서 순매출만 뺀 모양이다. */
@JvmRecord
data class PublicSpaceRankingResponse(
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
        val spaceId: Long,
        val spaceName: String,
        val location: String,
        val spaceStatus: SpaceStatus,
        val reservationCount: Int,
        val slotCount: Int,
    )

    companion object {
        @JvmStatic
        fun from(source: SpaceRankingResponse): PublicSpaceRankingResponse {
            val items =
                source.items
                    .stream()
                    .map { i ->
                        Item(
                            i.rank,
                            i.previousRank,
                            i.spaceId,
                            i.spaceName,
                            i.location,
                            i.spaceStatus,
                            i.reservationCount,
                            i.slotCount,
                        )
                    }.toList()
            return PublicSpaceRankingResponse(
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
