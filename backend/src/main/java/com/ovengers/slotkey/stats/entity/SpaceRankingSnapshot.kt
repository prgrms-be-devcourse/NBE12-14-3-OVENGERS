package com.ovengers.slotkey.stats.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "space_ranking_snapshot")
class SpaceRankingSnapshot protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    final var id: Long? = null
        private set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "period_type", nullable = false, length = 10)
    final lateinit var periodType: RankingPeriodType
        private set

    @field:Column(name = "period_start", nullable = false)
    final lateinit var periodStart: LocalDate
        private set

    @get:JvmName("getSpaceIdValue")
    @field:Column(name = "space_id", nullable = false)
    final var spaceId: Long = 0
        private set

    @field:Column(nullable = false)
    final lateinit var location: String
        private set

    @field:Column(name = "net_revenue", nullable = false)
    final var netRevenue: Long = 0
        private set

    @field:Column(name = "reservation_count", nullable = false)
    final var reservationCount: Int = 0
        private set

    @field:Column(name = "slot_count", nullable = false)
    final var slotCount: Int = 0
        private set

    @field:Column(name = "computed_at", nullable = false)
    final lateinit var computedAt: LocalDateTime
        private set

    constructor(
        periodType: RankingPeriodType,
        periodStart: LocalDate,
        spaceId: Long?,
        location: String,
        netRevenue: Long,
        reservationCount: Int,
        slotCount: Int,
        computedAt: LocalDateTime,
    ) : this() {
        this.periodType = periodType
        this.periodStart = periodStart
        this.spaceId = requireNotNull(spaceId)
        this.location = location
        this.netRevenue = netRevenue
        this.reservationCount = reservationCount
        this.slotCount = slotCount
        this.computedAt = computedAt
    }

    fun getSpaceId(): Long? = spaceId
}
