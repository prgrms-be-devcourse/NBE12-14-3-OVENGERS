
package com.ovengers.slotkey.inquiry.entity

import com.ovengers.slotkey.global.common.entity.BaseTimeEntity
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "inquiries")
class Inquiry(
    memberId: Long,
    title: String,
    content: String,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @field:Column(name = "member_id", nullable = false)
    var memberId: Long = memberId

    @field:Column(nullable = false, length = 200)
    var title: String = title

    @field:Column(nullable = false, columnDefinition = "TEXT")
    var content: String = content

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    var status: InquiryStatus = InquiryStatus.WAITING

    @field:Column(name = "answer_content", columnDefinition = "TEXT")
    var answerContent: String? = null

    @field:Column(name = "answered_by_member_id")
    var answeredByMemberId: Long? = null

    @field:Column(name = "answered_at")
    var answeredAt: LocalDateTime? = null

    fun updateContent(
        title: String,
        content: String,
    ) {
        if (status != InquiryStatus.WAITING) {
            throw BusinessException(ErrorCode.INQUIRY_ALREADY_ANSWERED)
        }

        this.title = title
        this.content = content
    }

    fun answer(
        adminMemberId: Long,
        answerContent: String,
        now: LocalDateTime,
    ) {
        this.answerContent = answerContent
        this.answeredByMemberId = adminMemberId
        this.answeredAt = now
        this.status = InquiryStatus.ANSWERED
    }

    companion object {
        @JvmStatic
        fun create(
            memberId: Long,
            title: String,
            content: String,
        ): Inquiry = Inquiry(memberId, title, content)
    }
}
