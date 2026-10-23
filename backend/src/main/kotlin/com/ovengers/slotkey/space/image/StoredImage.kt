package com.ovengers.slotkey.space.image

/** 저장된 공간 이미지 메타데이터. */
@JvmRecord
data class StoredImage(
    val fileName: String,
    val relativeUrl: String,
    val contentType: String,
    val size: Long,
    val width: Int,
    val height: Int,
)
