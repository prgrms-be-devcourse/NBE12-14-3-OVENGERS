package com.ovengers.slotkey.notification.service

import com.ovengers.slotkey.notification.dto.response.NotificationResponse
import com.ovengers.slotkey.notification.repository.NotificationRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/** 내 알림 조회. 모든 질의가 memberId 조건을 포함하므로 타인의 알림은 목록에도 개수에도 잡히지 않는다. */
@Service
@Transactional(readOnly = true)
class NotificationQueryService(
    private val notificationRepository: NotificationRepository,
    private val clock: Clock,
) {
    fun getMyNotifications(
        memberId: Long?,
        pageable: Pageable?,
    ): Page<NotificationResponse> =
        notificationRepository
            .findAllByMemberIdOrderByIdDesc(requireNotNull(memberId), requireNotNull(pageable))
            .map(NotificationResponse::from)

    fun countUnread(memberId: Long?): Long = notificationRepository.countByMemberIdAndReadAtIsNull(requireNotNull(memberId))

    @Transactional
    fun readAll(
        memberId: Long?,
        upToId: Long?,
    ): Int =
        notificationRepository.markAllRead(
            requireNotNull(memberId),
            requireNotNull(upToId),
            LocalDateTime.now(clock),
        )
}
