package com.ovengers.slotkey.stats.dto.response

import com.ovengers.slotkey.space.entity.SpaceStatus

@JvmRecord
data class SpaceRankingItem(
    val rank: Int,
    val previousRank: Int?,
    val spaceId: Long,
    val spaceName: String,
    val location: String,
    val spaceStatus: SpaceStatus,
    val netRevenue: Long,
    val reservationCount: Int,
    val slotCount: Int,
)
