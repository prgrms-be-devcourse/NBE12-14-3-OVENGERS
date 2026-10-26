package com.ovengers.slotkey.stats.service

import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.repository.SpaceRankingSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDateTime

/**
 * 서버가 꺼져 있었거나 계산이 실패해 확정 행이 없는 "끝난 기간"을 찾아 채운다.
 * 이미 행이 있는 기간은 다시 계산하지 않는다(확정값 유지). 행이 하나도 없는 기간은 거래가 없던 기간과
 * 계산을 못 한 기간을 구분할 수 없어 매번 다시 계산 대상이 된다(결과는 같고 비용은 쿼리 3개).
 * 각 기간은 [SpaceRankingConfirmService]가 자기 트랜잭션으로 처리하므로 이 클래스는 트랜잭션을 열지 않는다.
 */
@Service
class SpaceRankingBackfillService(
    private val confirmService: SpaceRankingConfirmService,
    private val snapshotRepository: SpaceRankingSnapshotRepository,
    private val clock: Clock,
    @Value("\${slotkey.scheduler.space-ranking.backfill-days:31}") private val backfillDays: Int,
) {
    /** @return 이번 호출에서 계산한 기간 수(행이 0개로 끝난 기간 포함) */
    fun backfillMissing(): Int {
        val now = LocalDateTime.now(clock)
        val today = now.toLocalDate()
        val windowStart = today.minusDays(backfillDays.toLong())
        var computed = 0

        for (type in RankingPeriodType.values()) {
            // windowStart를 포함하는 기간부터, 끝 날짜 00:01이 지난 기간만
            var periodStart = type.startOf(windowStart)!!
            while (SpaceRankingConfirmService.isConfirmable(type.endOf(periodStart), now)) {
                if (!snapshotRepository.existsByPeriodTypeAndPeriodStart(type, periodStart)) {
                    try {
                        confirmService.confirm(type, periodStart)
                        computed++
                    } catch (e: RuntimeException) {
                        log.error("랭킹 보충 실패: type={}, periodStart={}", type, periodStart, e)
                    }
                }
                periodStart = type.endOf(periodStart)
            }
        }
        return computed
    }

    companion object {
        private val log = LoggerFactory.getLogger(SpaceRankingBackfillService::class.java)
    }
}
