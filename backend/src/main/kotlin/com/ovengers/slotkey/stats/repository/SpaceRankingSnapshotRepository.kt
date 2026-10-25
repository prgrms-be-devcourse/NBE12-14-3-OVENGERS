package com.ovengers.slotkey.stats.repository

import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.SpaceRankingSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.Optional

interface SpaceRankingSnapshotRepository : JpaRepository<SpaceRankingSnapshot, Long> {
    fun existsByPeriodTypeAndPeriodStart(
        periodType: RankingPeriodType,
        periodStart: LocalDate,
    ): Boolean

    fun findByPeriodTypeAndPeriodStart(
        periodType: RankingPeriodType,
        periodStart: LocalDate,
    ): List<SpaceRankingSnapshot>

    @Query("SELECT MIN(s.periodStart) FROM SpaceRankingSnapshot s WHERE s.periodType = :periodType")
    fun findEarliestPeriodStart(
        @Param("periodType") periodType: RankingPeriodType,
    ): Optional<LocalDate>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM SpaceRankingSnapshot s WHERE s.periodType = :periodType AND s.periodStart = :periodStart")
    fun deleteByPeriod(
        @Param("periodType") periodType: RankingPeriodType,
        @Param("periodStart") periodStart: LocalDate,
    ): Int
}
