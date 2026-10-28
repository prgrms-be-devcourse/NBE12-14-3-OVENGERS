package com.ovengers.slotkey.notification

import com.ovengers.slotkey.notification.dto.response.NotificationResponse
import com.ovengers.slotkey.notification.service.NotificationQueryService
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest

/** 알림 조회: 최신순·페이징·본인 것만·안 읽은 개수. */
class NotificationQueryIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var service: NotificationQueryService

    @Test
    @DisplayName("내 알림만 최신순으로 페이징해 돌려주고, 타인의 알림은 보이지 않는다")
    fun getMyNotifications_onlyMine_newestFirst_paged() {
        val me = createMember(0)
        val other = createMember(0)
        val first = insert(me, 1L, "e1", "첫번째")
        val second = insert(me, 2L, "e2", "두번째")
        val third = insert(me, 3L, "e3", "세번째")
        insert(other, 4L, "e4", "남의 것")

        val page0 = service.getMyNotifications(me, PageRequest.of(0, 2))
        val page1 = service.getMyNotifications(me, PageRequest.of(1, 2))

        assertThat(page0.totalElements).isEqualTo(3)
        assertThat(page0.content).extracting<Long>(NotificationResponse::id).containsExactly(third, second)
        assertThat(page1.content).extracting<Long>(NotificationResponse::id).containsExactly(first)
        assertThat(page0.content).extracting<String>(NotificationResponse::message).doesNotContain("남의 것")
    }

    @Test
    @DisplayName("안 읽은 개수는 내 알림 중 read_at이 비어 있는 것만 센다")
    fun countUnread_countsOnlyMyUnread() {
        val me = createMember(0)
        val other = createMember(0)
        insert(me, 1L, "e1", "a")
        val read = insert(me, 2L, "e2", "b")
        insert(other, 3L, "e3", "c")
        jdbcTemplate.update("UPDATE notification SET read_at = NOW() WHERE id = ?", read)

        assertThat(service.countUnread(me)).isEqualTo(1)
        assertThat(service.countUnread(createMember(0))).isZero()
    }

    private fun insert(
        memberId: Long,
        entryId: Long,
        eventId: String,
        message: String,
    ): Long {
        jdbcTemplate.update(
            "INSERT INTO notification (member_id, waitlist_entry_id, reference_id, event_id, message, created_at) " +
                "VALUES (?, ?, ?, ?, ?, NOW())",
            memberId,
            entryId,
            entryId,
            eventId,
            message,
        )
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM notification WHERE member_id = ?", Long::class.javaObjectType, memberId)
    }
}
