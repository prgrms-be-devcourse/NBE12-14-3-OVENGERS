package com.ovengers.slotkey.inquiry.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@JvmRecord
data class InquiryCreateRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val title: String,
    @field:NotBlank
    @field:Size(max = 2000)
    val content: String,
)
