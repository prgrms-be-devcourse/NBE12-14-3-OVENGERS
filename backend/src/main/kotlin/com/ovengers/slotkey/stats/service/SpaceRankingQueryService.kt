package com.ovengers.slotkey.stats.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceRegion
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.stats.dto.response.LocationRankingItem
import com.ovengers.slotkey.stats.dto.response.LocationRankingResponse
import com.ovengers.slotkey.stats.dto.response.SpaceRankingItem
import com.ovengers.slotkey.stats.dto.response.SpaceRankingResponse
import com.ovengers.slotkey.stats.entity.RankingMetric
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.RankingStatus
import com.ovengers.slotkey.stats.entity.SpaceRankingSnapshot
import com.ovengers.slotkey.stats.repository.SpaceRankingSnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TreeMap

/**
 * 확정 스냅샷을 읽어 공간·지역 순위를 만든다. 순위(rank)는 저장하지 않고 여기서 지표별 동점 규칙으로 정렬해 매긴다.
 * 동점이어도 순위 번호는 겹치지 않는다. 근거: docs/decisions/space-ranking.md §2-6, §2-7, api-spec §14.
 */
@Service
@Transactional(readOnly = true)
class SpaceRankingQueryService(
    private val snapshotRepository: SpaceRankingSnapshotRepository,
    private val spaceRepository: SpaceRepository,
    private val clock: Clock,
) {
    fun getSpaceRanking(
        period: RankingPeriodType,
        date: LocalDate,
        metric: RankingMetric,
        location: String?,
        limit: Int,
    ): SpaceRankingResponse {
        validateLimit(limit)
        if (location != null && !SpaceRegion.isSupported(location)) {
            throw validationFailed("지원하지 않는 지역입니다.")
        }
        val view = resolve(period, date)
        if (view.status != RankingStatus.CONFIRMED) {
            return SpaceRankingResponse(
                period,
                view.start,
                view.end,
                metric,
                view.status,
                view.confirmAt,
                null,
                emptyList(),
            )
        }

        val ranked = sortedSpaceRows(period, view.start, metric, location)
        val previousRanks =
            rankBySpaceId(sortedSpaceRows(period, previousStart(period, view.start), metric, location))

        val top = ranked.subList(0, minOf(limit, ranked.size))
        val spaces = HashMap<Long, Space>()
        spaceRepository.findAllById(top.map { it.spaceId }).forEach { space ->
            spaces[space.id ?: return@forEach] = space
        }

        val items = ArrayList<SpaceRankingItem>()
        for (i in top.indices) {
            val row = top[i]
            // 확정 후 공간이 삭제될 수 있다(FK 없는 독립 집계, #186). 누락 행도 순위·지표를 보존해
            // 목록에서 빼지 않고, 표시 정보만 대체한다. 순위·limit은 전체 스냅샷 행 기준이다.
            val space = spaces[row.spaceId]
            items.add(
                SpaceRankingItem(
                    i + 1,
                    previousRanks[row.spaceId],
                    row.spaceId,
                    if (space == null) MISSING_SPACE_NAME else space.name ?: "",
                    row.location,
                    space?.status ?: SpaceStatus.INACTIVE,
                    row.netRevenue,
                    row.reservationCount,
                    row.slotCount,
                ),
            )
        }
        return SpaceRankingResponse(
            period,
            view.start,
            view.end,
            metric,
            view.status,
            null,
            view.computedAt,
            items,
        )
    }

    fun getLocationRanking(
        period: RankingPeriodType,
        date: LocalDate,
        metric: RankingMetric,
        limit: Int,
    ): LocationRankingResponse {
        validateLimit(limit)
        val view = resolve(period, date)
        if (view.status != RankingStatus.CONFIRMED) {
            return LocationRankingResponse(
                period,
                view.start,
                view.end,
                metric,
                view.status,
                view.confirmAt,
                null,
                emptyList(),
            )
        }

        val ranked = sortedLocationTotals(period, view.start, metric)
        val previousRanks = HashMap<String, Int>()
        val previous = sortedLocationTotals(period, previousStart(period, view.start), metric)
        for (i in previous.indices) {
            previousRanks[previous[i].location] = i + 1
        }

        val items = ArrayList<LocationRankingItem>()
        for (i in 0 until minOf(limit, ranked.size)) {
            val total = ranked[i]
            items.add(
                LocationRankingItem(
                    i + 1,
                    previousRanks[total.location],
                    total.location,
                    total.spaceCount,
                    total.netRevenue,
                    total.reservationCount,
                    total.slotCount,
                ),
            )
        }
        return LocationRankingResponse(
            period,
            view.start,
            view.end,
            metric,
            view.status,
            null,
            view.computedAt,
            items,
        )
    }

    // ---------- 기간 상태 ----------

    private fun resolve(
        period: RankingPeriodType,
        date: LocalDate,
    ): PeriodView {
        val today = LocalDate.now(clock)
        val start = period.startOf(date)!!
        val end = period.endOf(date)

        if (start.isAfter(period.startOf(today)!!)) {
            throw validationFailed("현재 기간보다 미래의 날짜는 조회할 수 없습니다.")
        }
        if (end.isAfter(today)) {
            return PeriodView(start, end, RankingStatus.PENDING, end.atStartOfDay(), null)
        }

        val rows = snapshotRepository.findByPeriodTypeAndPeriodStart(period, start)
        if (rows.isNotEmpty()) {
            val computedAt = rows.maxOfOrNull { it.computedAt }
            return PeriodView(start, end, RankingStatus.CONFIRMED, null, computedAt)
        }
        // 기간 끝 날짜 00:00~00:01은 아직 확정 전이다. 빈 CONFIRMED("거래 없음")로 보이지 않게 PENDING으로 둔다.
        if (LocalDateTime.now(clock).isBefore(end.atTime(SpaceRankingConfirmService.CONFIRM_TIME))) {
            return PeriodView(start, end, RankingStatus.PENDING, end.atStartOfDay(), null)
        }
        // 행이 없으면 "거래가 없던 기간"과 "확정 기록이 시작되기 전"을 구분해야 한다.
        // 해당 종류의 가장 이른 확정 기간보다 앞이거나 기록이 전혀 없으면 UNAVAILABLE이다.
        val beforeRecords =
            snapshotRepository.findEarliestPeriodStart(period).map { start.isBefore(it) }.orElse(true)
        return PeriodView(
            start,
            end,
            if (beforeRecords) RankingStatus.UNAVAILABLE else RankingStatus.CONFIRMED,
            null,
            null,
        )
    }

    private fun previousStart(
        period: RankingPeriodType,
        start: LocalDate,
    ): LocalDate = period.startOf(start.minusDays(1))!!

    // ---------- 정렬 ----------

    private fun sortedSpaceRows(
        period: RankingPeriodType,
        start: LocalDate,
        metric: RankingMetric,
        location: String?,
    ): List<SpaceRankingSnapshot> =
        snapshotRepository
            .findByPeriodTypeAndPeriodStart(period, start)
            .filter { row -> location == null || location == row.location }
            .sortedWith(spaceOrder(metric))

    private fun rankBySpaceId(sorted: List<SpaceRankingSnapshot>): Map<Long, Int> {
        val ranks = HashMap<Long, Int>()
        for (i in sorted.indices) {
            ranks[sorted[i].spaceId] = i + 1
        }
        return ranks
    }

    /** NET_REVENUE: 순매출→건수→spaceId, RESERVATION_COUNT: 건수→순매출→spaceId, SLOT_COUNT: 슬롯→건수→spaceId. */
    private fun spaceOrder(metric: RankingMetric): Comparator<SpaceRankingSnapshot> {
        val revenue = compareByDescending<SpaceRankingSnapshot> { it.netRevenue }
        val count = compareByDescending<SpaceRankingSnapshot> { it.reservationCount }
        val slots = compareByDescending<SpaceRankingSnapshot> { it.slotCount }
        val id = compareBy<SpaceRankingSnapshot> { it.spaceId }
        return when (metric) {
            RankingMetric.NET_REVENUE -> revenue.thenComparing(count).thenComparing(id)
            RankingMetric.RESERVATION_COUNT -> count.thenComparing(revenue).thenComparing(id)
            RankingMetric.SLOT_COUNT -> slots.thenComparing(count).thenComparing(id)
        }
    }

    private fun sortedLocationTotals(
        period: RankingPeriodType,
        start: LocalDate,
        metric: RankingMetric,
    ): List<LocationTotal> {
        val totals = TreeMap<String, LocationTotal>()
        for (row in snapshotRepository.findByPeriodTypeAndPeriodStart(period, start)) {
            totals.merge(row.location, LocationTotal.of(row), LocationTotal::plus)
        }
        return totals.values.sortedWith(locationOrder(metric))
    }

    /** 공간 규칙과 같고, 마지막 키만 spaceId 대신 location 가나다순이다. */
    private fun locationOrder(metric: RankingMetric): Comparator<LocationTotal> {
        val revenue = compareByDescending<LocationTotal> { it.netRevenue }
        val count = compareByDescending<LocationTotal> { it.reservationCount }
        val slots = compareByDescending<LocationTotal> { it.slotCount }
        val name = compareBy<LocationTotal> { it.location }
        return when (metric) {
            RankingMetric.NET_REVENUE -> revenue.thenComparing(count).thenComparing(name)
            RankingMetric.RESERVATION_COUNT -> count.thenComparing(revenue).thenComparing(name)
            RankingMetric.SLOT_COUNT -> slots.thenComparing(count).thenComparing(name)
        }
    }

    // ---------- 검증·내부 타입 ----------

    private fun validateLimit(limit: Int) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw validationFailed("limit은 1 이상 $MAX_LIMIT 이하여야 합니다.")
        }
    }

    private fun validationFailed(message: String): BusinessException =
        BusinessException(
            ErrorCode.VALIDATION_FAILED,
            message,
        )

    private data class PeriodView(
        val start: LocalDate,
        val end: LocalDate,
        val status: RankingStatus,
        val confirmAt: LocalDateTime?,
        val computedAt: LocalDateTime?,
    )

    private data class LocationTotal(
        val location: String,
        val spaceCount: Int,
        val netRevenue: Long,
        val reservationCount: Int,
        val slotCount: Int,
    ) {
        fun plus(other: LocationTotal): LocationTotal =
            LocationTotal(
                location,
                spaceCount + other.spaceCount,
                netRevenue + other.netRevenue,
                reservationCount + other.reservationCount,
                slotCount + other.slotCount,
            )

        companion object {
            fun of(row: SpaceRankingSnapshot): LocationTotal =
                LocationTotal(row.location, 1, row.netRevenue, row.reservationCount, row.slotCount)
        }
    }

    companion object {
        private const val MAX_LIMIT = 50
        private const val MISSING_SPACE_NAME = "삭제된 공간"
    }
}
