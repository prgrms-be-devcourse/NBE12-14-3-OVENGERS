package com.ovengers.slotkey.notification

import com.ovengers.slotkey.notification.consumer.NotificationEventHandler
import com.ovengers.slotkey.notification.entity.NotificationType
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class NotificationEventHandlerIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired private lateinit var handler: NotificationEventHandler

    private val eventIds = mutableListOf<String>()

    @AfterEach
    fun cleanEvents() {
        eventIds.forEach {
            jdbcTemplate.update("DELETE FROM notification WHERE event_id = ?", it)
            jdbcTemplate.update("DELETE FROM processed_event WHERE event_id = ?", it)
        }
    }

    @Test
    fun savesNotificationForActiveMember() {
        val member = createMember(0)

        handler.handle(event(), NotificationType.CREDIT_GRANTED, member, 7L, "크레딧이 지급되었습니다.")

        val row =
            jdbcTemplate.queryForMap(
                "SELECT type, reference_id, waitlist_entry_id, message, read_at FROM notification WHERE member_id = ?",
                member,
            )
        assertThat(row["type"]).isEqualTo("CREDIT_GRANTED")
        assertThat((row["reference_id"] as Number).toLong()).isEqualTo(7L)
        assertThat(row["waitlist_entry_id"]).isNull()
        assertThat(row["message"]).isEqualTo("크레딧이 지급되었습니다.")
        assertThat(row["read_at"]).isNull()
    }

    @Test
    fun redeliveredEventCreatesOnlyOneNotification() {
        val member = createMember(0)
        val eventId = event()

        handler.handle(eventId, NotificationType.INQUIRY_ANSWERED, member, 3L, "답변")
        handler.handle(eventId, NotificationType.INQUIRY_ANSWERED, member, 3L, "답변")

        assertThat(count(member)).isEqualTo(1)
    }

    @Test
    fun skipsMemberThatIsNotActive() {
        val suspended = createMember(0)
        jdbcTemplate.update("UPDATE member SET status = 'SUSPENDED' WHERE id = ?", suspended)

        handler.handle(event(), NotificationType.RESERVATION_FORCE_CANCELLED, suspended, 1L, "취소")

        assertThat(count(suspended)).isZero()
    }

    @Test
    fun skipsUnknownMember() {
        // 예외 없이 건너뛰어야 한다(탈퇴 등으로 수신자가 사라진 경우 재시도 루프를 만들지 않는다).
        handler.handle(event(), NotificationType.CREDIT_GRANTED, Long.MAX_VALUE - 1, 1L, "m")
    }

    @Test
    fun nullRecipientFansOutToActiveAdminsOnly() {
        val activeAdmin = createMember(0)
        val suspendedAdmin = createMember(0)
        val activeUser = createMember(0)
        jdbcTemplate.update("UPDATE member SET role = 'ADMIN' WHERE id IN (?, ?)", activeAdmin, suspendedAdmin)
        jdbcTemplate.update("UPDATE member SET status = 'SUSPENDED' WHERE id = ?", suspendedAdmin)

        handler.handle(event(), NotificationType.INQUIRY_CREATED, null, 9L, "새 문의가 등록되었습니다.")

        assertThat(count(activeAdmin)).isEqualTo(1)
        assertThat(count(suspendedAdmin)).isZero()
        assertThat(count(activeUser)).isZero()
    }

    private fun event(): String =
        java.util.UUID
            .randomUUID()
            .toString()
            .also { eventIds += it }

    private fun count(memberId: Long): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE member_id = ?", Int::class.javaObjectType, memberId)!!
}
