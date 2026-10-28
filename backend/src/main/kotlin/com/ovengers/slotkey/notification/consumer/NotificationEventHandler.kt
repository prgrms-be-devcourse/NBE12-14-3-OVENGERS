package com.ovengers.slotkey.notification.consumer

import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.notification.entity.Notification
import com.ovengers.slotkey.notification.entity.NotificationType
import com.ovengers.slotkey.notification.repository.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * `notification-events` 이벤트 처리. 한 트랜잭션에서 processed_event 선점과 알림 저장을 한다.
 * 수신자는 payload의 `recipientMemberId`이고, null이면 소비 시점의 ACTIVE 관리자 전원이다.
 * 수신자가 없거나 ACTIVE가 아니면 건너뛴다(예외를 던지면 재시도만 반복된다).
 */
@Service
class NotificationEventHandler(
    private val processedEventStore: ProcessedEventStore,
    private val memberRepository: MemberRepository,
    private val notificationRepository: NotificationRepository,
    private val clock: Clock,
) {
    @Transactional
    fun handle(
        eventId: String,
        type: NotificationType,
        recipientMemberId: Long?,
        referenceId: Long,
        message: String,
    ) {
        if (!processedEventStore.markProcessed(CONSUMER, eventId)) return

        val now = LocalDateTime.now(clock)
        recipientIds(recipientMemberId).forEach { memberId ->
            notificationRepository.save(Notification.of(memberId, type, referenceId, eventId, message, now))
        }
    }

    private fun recipientIds(recipientMemberId: Long?): List<Long> =
        if (recipientMemberId == null) {
            memberRepository.findAllByRoleAndStatus(MemberRole.ADMIN, MemberStatus.ACTIVE).map { requireNotNull(it.id) }
        } else {
            listOfNotNull(
                memberRepository
                    .findById(recipientMemberId)
                    .filter { it.status == MemberStatus.ACTIVE }
                    .map { requireNotNull(it.id) }
                    .orElse(null),
            )
        }

    companion object {
        const val CONSUMER = "notification-notifier"
    }
}
