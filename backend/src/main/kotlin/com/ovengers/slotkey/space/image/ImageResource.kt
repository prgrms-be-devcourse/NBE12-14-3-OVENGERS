package com.ovengers.slotkey.space.image

import org.springframework.core.io.Resource

/** 로드된 이미지 리소스 (서빙용). */
@JvmRecord
data class ImageResource(
    val resource: Resource,
    val bytes: ByteArray,
    val contentType: String,
    val contentLength: Long,
)
