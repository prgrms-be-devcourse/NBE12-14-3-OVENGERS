package com.ovengers.slotkey.stats.dto.response

@JvmRecord
data class LocationRankingItem(
    val rank: Int,
    val previousRank: Int?,
    val location: String,
    val spaceCount: Int,
    val netRevenue: Long,
    val reservationCount: Int,
    val slotCount: Int,
)
