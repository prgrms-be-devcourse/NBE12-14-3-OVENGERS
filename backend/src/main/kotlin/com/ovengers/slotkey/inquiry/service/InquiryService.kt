
package com.ovengers.slotkey.inquiry.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.inquiry.authorization.InquiryAuthorizationService
import com.ovengers.slotkey.inquiry.dto.request.InquiryCreateRequest
import com.ovengers.slotkey.inquiry.dto.request.InquiryUpdateRequest
import com.ovengers.slotkey.inquiry.entity.Inquiry
import com.ovengers.slotkey.inquiry.repository.InquiryRepository
import com.ovengers.slotkey.notification.entity.NotificationType
import com.ovengers.slotkey.outbox.NotificationEventRecorder
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class InquiryService(
    private val inquiryRepository: InquiryRepository,
    private val inquiryAuthorizationService: InquiryAuthorizationService,
    private val notificationEventRecorder: NotificationEventRecorder,
) {
    @Transactional
    fun create(
        memberId: Long,
        request: InquiryCreateRequest,
    ): Inquiry =
        Inquiry
            .create(memberId, request.title, request.content)
            .let(inquiryRepository::save)
            .also { inquiry ->
                // 수신자 null = 소비 시점의 ACTIVE 관리자 전원
                notificationEventRecorder.record(
                    NotificationType.INQUIRY_CREATED,
                    null,
                    requireNotNull(inquiry.id),
                    "새 문의가 등록되었습니다: '${inquiry.title.take(MAX_TITLE_LENGTH)}'",
                )
            }

    fun getMyInquiries(
        memberId: Long,
        pageable: Pageable,
    ): Page<Inquiry> = inquiryRepository.findAllByMemberId(memberId, pageable)

    fun getMyInquiry(
        memberId: Long,
        inquiryId: Long,
    ): Inquiry {
        val inquiry = getOrThrow(inquiryId)
        inquiryAuthorizationService.validateOwner(memberId, inquiry.memberId)
        return inquiry
    }

    @Transactional
    fun update(
        memberId: Long,
        inquiryId: Long,
        request: InquiryUpdateRequest,
    ): Inquiry =
        getOrThrow(inquiryId).also { inquiry ->
            inquiryAuthorizationService.validateOwner(memberId, inquiry.memberId)
            inquiry.updateContent(request.title, request.content)
        }

    private fun getOrThrow(inquiryId: Long): Inquiry =
        inquiryRepository.findByIdOrNull(inquiryId)
            ?: throw BusinessException(ErrorCode.INQUIRY_NOT_FOUND)

    private companion object {
        const val MAX_TITLE_LENGTH = 50
    }
}
