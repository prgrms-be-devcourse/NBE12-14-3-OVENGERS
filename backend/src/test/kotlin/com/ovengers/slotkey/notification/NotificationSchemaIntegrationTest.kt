package com.ovengers.slotkey.notification

import com.ovengers.slotkey.notification.entity.Notification
import com.ovengers.slotkey.notification.entity.NotificationType
import com.ovengers.slotkey.notification.repository.NotificationRepository
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import java.time.LocalDateTime

class NotificationSchemaIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Test
    fun waitlistRowDefaultsToSlotReleasedType() {
        val member = createMember(0)
        insertRaw(member, "e1", waitlistEntryId = 7L, referenceId = 7L)

        val row = jdbcTemplate.queryForMap("SELECT type, reference_id FROM notification WHERE member_id = ?", member)
        assertThat(row["type"]).isEqualTo("WAITLIST_SLOT_RELEASED")
        assertThat((row["reference_id"] as Number).toLong()).isEqualTo(7L)
    }

    @Test
    fun nonWaitlistRowAllowsNullWaitlistEntry() {
        val member = createMember(0)
        insertRaw(member, "e2", waitlistEntryId = null, referenceId = 9L, type = "CREDIT_GRANTED")

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE member_id = ?", Int::class.javaObjectType, member))
            .isEqualTo(1)
    }

    @Test
    fun sameEventMemberReferenceIsRejected() {
        val member = createMember(0)
        insertRaw(member, "e3", waitlistEntryId = null, referenceId = 5L, type = "INQUIRY_CREATED")

        assertThatThrownBy { insertRaw(member, "e3", waitlistEntryId = null, referenceId = 5L, type = "INQUIRY_CREATED") }
            .isInstanceOf(DuplicateKeyException::class.java)
    }

    @Test
    fun sameEventAndMemberWithDifferentReferenceIsAllowed() {
        // 한 SlotReleased 이벤트가 같은 회원의 서로 다른 대기 항목 두 건에 알림을 만드는 기존 동작을 막지 않는다.
        val member = createMember(0)
        insertRaw(member, "e4", waitlistEntryId = 1L, referenceId = 1L)
        insertRaw(member, "e4", waitlistEntryId = 2L, referenceId = 2L)

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE member_id = ?", Int::class.javaObjectType, member))
            .isEqualTo(2)
    }

    @Test
    fun legacyConstructorMirrorsWaitlistEntryIntoReference() {
        val member = createMember(0)
        val saved = notificationRepository.saveAndFlush(Notification(member, 11L, "e5", "빈자리", LocalDateTime.of(2026, 10, 28, 9, 0)))

        assertThat(saved.type).isEqualTo(NotificationType.WAITLIST_SLOT_RELEASED)
        assertThat(saved.referenceId).isEqualTo(11L)
        assertThat(saved.waitlistEntryId).isEqualTo(11L)
    }

    @Test
    fun eventNotificationStoresTypeAndReferenceWithoutWaitlistEntry() {
        val member = createMember(0)
        val saved =
            notificationRepository.saveAndFlush(
                Notification.of(member, NotificationType.CREDIT_GRANTED, 3L, "e6", "크레딧이 지급되었습니다.", LocalDateTime.of(2026, 10, 28, 9, 0)),
            )

        val reloaded = notificationRepository.findById(requireNotNull(saved.id)).orElseThrow()
        assertThat(reloaded.type).isEqualTo(NotificationType.CREDIT_GRANTED)
        assertThat(reloaded.referenceId).isEqualTo(3L)
        assertThat(reloaded.waitlistEntryId).isNull()
    }

    private fun insertRaw(
        memberId: Long,
        eventId: String,
        waitlistEntryId: Long?,
        referenceId: Long,
        type: String? = null,
    ) {
        if (type == null) {
            jdbcTemplate.update(
                "INSERT INTO notification (member_id, waitlist_entry_id, reference_id, event_id, message, created_at) VALUES (?, ?, ?, ?, 'm', NOW())",
                memberId,
                waitlistEntryId,
                referenceId,
                eventId,
            )
        } else {
            jdbcTemplate.update(
                "INSERT INTO notification (member_id, waitlist_entry_id, type, reference_id, event_id, message, created_at) VALUES (?, ?, ?, ?, ?, 'm', NOW())",
                memberId,
                waitlistEntryId,
                type,
                referenceId,
                eventId,
            )
        }
    }
}
