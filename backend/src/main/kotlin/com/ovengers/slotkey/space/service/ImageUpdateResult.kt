package com.ovengers.slotkey.space.service

import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse

@JvmRecord
data class ImageUpdateResult(
    val oldImagePath: String?,
    val spaceDetailResponse: SpaceDetailResponse?,
) {
    override fun toString(): String = "ImageUpdateResult[oldImagePath=$oldImagePath, spaceDetailResponse=$spaceDetailResponse]"
}
