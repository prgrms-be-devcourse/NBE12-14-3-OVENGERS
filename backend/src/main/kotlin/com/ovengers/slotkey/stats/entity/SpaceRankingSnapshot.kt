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
    var id: Long? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "period_type", nullable = false, length = 10)
    lateinit var periodType: RankingPeriodType
        protected set

    @field:Column(name = "period_start", nullable = false)
    lateinit var periodStart: LocalDate
        protected set

    @Suppress("INAPPLICABLE_JVM_NAME")
    @get:JvmName("getSpaceIdValue")
    @field:Column(name = "space_id", nullable = false)
    var spaceId: Long = 0
        protected set

    @field:Column(nullable = false)
    lateinit var location: String
        protected set

    @field:Column(name = "net_revenue", nullable = false)
    var netRevenue: Long = 0
        protected set

    @field:Column(name = "reservation_count", nullable = false)
    var reservationCount: Int = 0
        protected set

    @field:Column(name = "slot_count", nullable = false)
    var slotCount: Int = 0
        protected set

    @field:Column(name = "computed_at", nullable = false)
    lateinit var computedAt: LocalDateTime
        protected set

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
