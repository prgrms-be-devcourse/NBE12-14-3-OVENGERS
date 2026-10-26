package com.ovengers.slotkey.inquiry.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@JvmRecord
data class InquiryAnswerRequest(
    @field:NotBlank
    @field:Size(max = 2000)
    val content: String,
)
