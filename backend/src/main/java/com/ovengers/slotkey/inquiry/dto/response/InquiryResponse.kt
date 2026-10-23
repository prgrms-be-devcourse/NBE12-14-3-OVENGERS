package com.ovengers.slotkey.inquiry.dto.response

import com.ovengers.slotkey.inquiry.entity.Inquiry
import com.ovengers.slotkey.inquiry.entity.InquiryStatus
import java.time.LocalDateTime

@JvmRecord
data class InquiryResponse(
    val id: Long,
    val memberId: Long,
    val title: String,
    val content: String,
    val status: InquiryStatus,
    val answerContent: String?,
    val answeredByMemberId: Long?,
    val answeredAt: LocalDateTime?,
    val createdAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(inquiry: Inquiry) =
            InquiryResponse(
                inquiry.getId(),
                inquiry.getMemberId(),
                inquiry.getTitle(),
                inquiry.getContent(),
                inquiry.getStatus(),
                inquiry.getAnswerContent(),
                inquiry.getAnsweredByMemberId(),
                inquiry.getAnsweredAt(),
                inquiry.getCreatedAt(),
            )
    }
}
