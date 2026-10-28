
package com.ovengers.slotkey.inquiry.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.inquiry.dto.request.InquiryAnswerRequest
import com.ovengers.slotkey.inquiry.entity.Inquiry
import com.ovengers.slotkey.inquiry.entity.InquiryStatus
import com.ovengers.slotkey.inquiry.repository.InquiryRepository
import com.ovengers.slotkey.notification.entity.NotificationType
import com.ovengers.slotkey.outbox.NotificationEventRecorder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class AdminInquiryService(
    private val inquiryRepository: InquiryRepository,
    private val notificationEventRecorder: NotificationEventRecorder,
    private val clock: Clock,
) {
    fun getInquiries(
        status: InquiryStatus?,
        pageable: Pageable,
    ): Page<Inquiry> =
        if (status == null) {
            inquiryRepository.findAll(pageable)
        } else {
            inquiryRepository.findAllByStatus(status, pageable)
        }

    fun getInquiry(inquiryId: Long): Inquiry =
        inquiryRepository.findByIdOrNull(inquiryId)
            ?: throw BusinessException(ErrorCode.INQUIRY_NOT_FOUND)

    @Transactional
    fun answer(
        inquiryId: Long,
        adminMemberId: Long,
        request: InquiryAnswerRequest,
    ): Inquiry =
        getInquiry(inquiryId).also { inquiry ->
            inquiry.answer(
                adminMemberId,
                request.content,
                LocalDateTime.now(clock),
            )
            notificationEventRecorder.record(
                NotificationType.INQUIRY_ANSWERED,
                inquiry.memberId,
                requireNotNull(inquiry.id),
                "문의 '${inquiry.title.take(MAX_TITLE_LENGTH)}'에 답변이 등록되었습니다.",
            )
        }

    private companion object {
        const val MAX_TITLE_LENGTH = 50
    }
}
