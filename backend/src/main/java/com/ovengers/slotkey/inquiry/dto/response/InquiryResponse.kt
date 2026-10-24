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
                inquiry.id
                    ?: error("저장되지 않은 문의는 응답으로 변환할 수 없습니다."),
                inquiry.memberId,
                inquiry.title,
                inquiry.content,
                inquiry.status,
                inquiry.answerContent,
                inquiry.answeredByMemberId,
                inquiry.answeredAt,
                inquiry.getCreatedAt(),
            )
    }
}
