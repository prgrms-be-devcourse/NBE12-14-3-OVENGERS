package com.ovengers.slotkey.stats.repository

@JvmRecord
data class SpaceRankingAggregate(
    val spaceId: Long,
    val location: String?,
    val netRevenue: Long,
    val reservationCount: Int,
    val slotCount: Int,
) {
    fun isAllZero(): Boolean = netRevenue == 0L && reservationCount == 0 && slotCount == 0
}
