package com.ovengers.slotkey.notification.consumer

import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.notification.entity.Notification
import com.ovengers.slotkey.notification.repository.NotificationRepository
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.waitlist.entity.WaitlistEntry
import com.ovengers.slotkey.waitlist.entity.WaitlistStatus
import com.ovengers.slotkey.waitlist.repository.WaitlistEntryRepository
import com.ovengers.slotkey.waitlist.service.ReservationOccupancyProvider
import com.ovengers.slotkey.waitlist.service.ReservationRangeProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * SlotReleased 이벤트 처리. 한 트랜잭션에서 processed_event 선점, 대상 선정, 알림 저장과 알림 시각 기록을 한다.
 * 알림 대상은 payload를 믿지 않고 현재 DB를 다시 읽어 고른다.
 */
@Service
class SlotReleasedHandler(
    private val processedEventStore: ProcessedEventStore,
    private val reservationRangeProvider: ReservationRangeProvider,
    private val reservationOccupancyProvider: ReservationOccupancyProvider,
    private val waitlistEntryRepository: WaitlistEntryRepository,
    private val memberRepository: MemberRepository,
    private val spaceRepository: SpaceRepository,
    private val notificationRepository: NotificationRepository,
    private val clock: Clock,
) {
    @Transactional
    fun handle(
        eventId: String?,
        reservationId: Long?,
    ) {
        val requiredEventId = requireNotNull(eventId)
        val requiredReservationId = requireNotNull(reservationId)
        if (!processedEventStore.markProcessed(CONSUMER, requiredEventId)) return

        val released = reservationRangeProvider.findRange(requiredReservationId)
        if (released == null) {
            logger.warn(
                "SlotReleased for unknown reservation: eventId={}, reservationId={}",
                requiredEventId,
                requiredReservationId,
            )
            return
        }

        val now = LocalDateTime.now(clock)
        val releasedSpaceId = requireNotNull(released.spaceId)
        val releasedStartTime = requireNotNull(released.startTime)
        val releasedEndTime = requireNotNull(released.endTime)
        val candidates =
            waitlistEntryRepository.findNotificationCandidates(
                releasedSpaceId,
                releasedStartTime,
                releasedEndTime,
                WaitlistStatus.WAITING,
                now,
            )
        if (candidates.isEmpty()) return

        val spaceName = spaceRepository.findById(releasedSpaceId).map { it.name ?: "공간" }.orElse("공간")
        candidates.filter { isNotifiable(it, now) }.forEach { entry ->
            val entryId = requireNotNull(entry.id)
            notificationRepository.save(
                Notification(entry.memberId, entryId, requiredEventId, message(spaceName, entry), now),
            )
            waitlistEntryRepository.markNotified(entryId, now, WaitlistStatus.WAITING)
        }
    }

    /** 신청 구간 전체가 지금 비어 있고, 회원이 ACTIVE이며, 본인이 겹치는 점유 예약을 갖고 있지 않은 항목만 대상이다. */
    private fun isNotifiable(
        entry: WaitlistEntry,
        now: LocalDateTime,
    ): Boolean {
        val member = memberRepository.findById(entry.memberId).orElse(null)
        if (member == null || member.status != MemberStatus.ACTIVE) return false
        if (
            reservationOccupancyProvider.hasOwnOccupyingReservationOverlapping(
                entry.memberId,
                entry.spaceId,
                entry.startTime,
                entry.endTime,
                now,
            )
        ) {
            return false
        }
        return !reservationOccupancyProvider.hasOccupiedSlot(
            entry.spaceId,
            entry.startTime,
            entry.endTime,
            now,
        )
    }

    private fun message(
        spaceName: String,
        entry: WaitlistEntry,
    ): String =
        "[$spaceName] ${entry.startTime.format(DATE_TIME)}~${entry.endTime.format(TIME)} " +
            "자리가 비었습니다. 대기하신 모든 분께 알리는 선착순 안내이며, 예약이 보장되지는 않습니다."

    companion object {
        const val CONSUMER = "waitlist-notifier"
        private val logger = LoggerFactory.getLogger(SlotReleasedHandler::class.java)
        private val DATE_TIME = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    }
}
