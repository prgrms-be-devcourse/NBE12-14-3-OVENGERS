package com.ovengers.slotkey.inquiry.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * WAITING 상태의 본인 문의만 수정 가능(서비스에서 검증).
 */
@JvmRecord
data class InquiryUpdateRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val title: String,
    @field:NotBlank
    @field:Size(max = 2000)
    val content: String,
)
