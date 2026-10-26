package com.ovengers.slotkey.stats.service

import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.SpaceRankingSnapshot
import com.ovengers.slotkey.stats.repository.SpaceRankingAggregationRepository
import com.ovengers.slotkey.stats.repository.SpaceRankingSnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.locks.ReentrantLock

/**
 * 끝난 기간의 공간별 집계를 계산해 확정값(space_ranking_snapshot)으로 저장한다.
 * 같은 기간을 다시 계산하면 그 기간의 기존 행을 지우고 다시 넣으므로 결과가 중복되지 않는다(멱등).
 * 근거: docs/decisions/space-ranking.md. 스케줄링·빠진 기간 보충은 단계 4에서 이 서비스를 호출한다.
 */
@Service
class SpaceRankingConfirmService(
    private val aggregationRepository: SpaceRankingAggregationRepository,
    private val snapshotRepository: SpaceRankingSnapshotRepository,
    private val clock: Clock,
) {
    private val confirmLock = ReentrantLock(true)

    /**
     * @param dateInPeriod 확정할 기간에 속한 아무 날짜(KST)
     * @return 저장한 행 수
     * @throws IllegalArgumentException 기간이 아직 끝나지 않은 경우(기간 끝 날짜 00:01 이후에만 확정 가능)
     */
    @Transactional
    fun confirm(
        periodType: RankingPeriodType,
        dateInPeriod: LocalDate,
    ): Int {
        val periodStart = periodType.startOf(dateInPeriod)!!
        val periodEnd = periodType.endOf(dateInPeriod)
        val now = LocalDateTime.now(clock)

        if (!isConfirmable(periodEnd, now)) {
            throw IllegalArgumentException(
                "아직 끝나지 않은 기간은 확정할 수 없습니다: %s %s ~ %s".format(periodType, periodStart, periodEnd),
            )
        }

        confirmLock.lock()
        var lockHandledByTxSync = false
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive() &&
                TransactionSynchronizationManager.isSynchronizationActive()
            ) {
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun afterCompletion(status: Int) {
                            confirmLock.unlock()
                        }
                    },
                )
                lockHandledByTxSync = true
            }

            val aggregates =
                aggregationRepository.aggregate(periodStart.atStartOfDay(), periodEnd.atStartOfDay())

            snapshotRepository.deleteByPeriod(periodType, periodStart)
            val snapshots =
                aggregates.map { a ->
                    SpaceRankingSnapshot(
                        periodType,
                        periodStart,
                        a.spaceId,
                        a.location!!,
                        a.netRevenue,
                        a.reservationCount,
                        a.slotCount,
                        now,
                    )
                }
            snapshotRepository.saveAll(snapshots)
            return snapshots.size
        } finally {
            if (!lockHandledByTxSync) {
                confirmLock.unlock()
            }
        }
    }

    companion object {
        /** 계산 시작 시각. 기간 끝 날짜의 이 시각부터 확정 대상이다(커밋 지연 완충). */
        @JvmField
        val CONFIRM_TIME: LocalTime = LocalTime.of(0, 1)

        /** 기간 끝 날짜 00:01 이후면 확정 대상이다. 보충도 같은 기준을 쓴다. */
        @JvmStatic
        fun isConfirmable(
            periodEnd: LocalDate,
            now: LocalDateTime,
        ): Boolean = !now.isBefore(periodEnd.atTime(CONFIRM_TIME))
    }
}
