package com.ovengers.slotkey.notification

import com.ovengers.slotkey.notification.consumer.SlotReleasedHandler
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.waitlist.entity.WaitlistEntry
import com.ovengers.slotkey.waitlist.repository.WaitlistEntryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.ArrayList
import java.util.UUID

/**
 * 같은 이벤트가 동시에 여러 번 전달돼도(at-least-once + 컨슈머 재시작·리밸런싱) 알림이 한 번만 생기는지
 * 실제 MySQL에서 검증한다. 판정자는 processed_event의 UNIQUE(consumer, event_id)이며,
 * 두 번째 INSERT IGNORE는 첫 트랜잭션이 끝날 때까지 기다린 뒤 0을 받는다.
 */
class SlotReleasedHandlerConcurrencyTest : ReservationIntegrationTestSupport() {
    private companion object {
        private const val THREADS = 4
    }

    @Autowired
    private lateinit var handler: SlotReleasedHandler

    @Autowired
    private lateinit var waitlistEntryRepository: WaitlistEntryRepository

    private val eventId = UUID.randomUUID().toString()

    @AfterEach
    fun cleanProcessedEvents() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE event_id = ?", eventId)
    }

    @Test
    @DisplayName("같은 eventId를 4개 스레드가 동시에 처리해도 예외 없이 알림은 대기자당 1건, 처리 기록은 1건이다")
    fun handle_sameEventConcurrently_notifiesOnce() {
        val spaceId = createSpace()
        val occupierId = createMember(0)
        val start = tomorrowAt(14, 0)
        val end = tomorrowAt(15, 0)
        val reservationId = createReservation(occupierId, spaceId, ReservationStatus.CONFIRMED, start, end)
        jdbcTemplate.update(
            "UPDATE reservation SET status = 'CANCELLED', cancelled_at = NOW() WHERE id = ?",
            reservationId,
        )
        jdbcTemplate.update("DELETE FROM reservation_slot WHERE reservation_id = ?", reservationId)
        val entry1 =
            waitlistEntryRepository
                .saveAndFlush(
                    WaitlistEntry.waiting(createMember(0), spaceId, start, end, now()),
                ).id!!
        val entry2 =
            waitlistEntryRepository
                .saveAndFlush(
                    WaitlistEntry.waiting(createMember(0), spaceId, start, end, now()),
                ).id!!

        val tasks = ArrayList<Runnable>()
        for (i in 0 until THREADS) {
            tasks.add(Runnable { handler.handle(eventId, reservationId) })
        }
        val results = runConcurrently(tasks)

        assertThat(failures(results)).isEmpty()
        assertThat(countFor(entry1)).isEqualTo(1)
        assertThat(countFor(entry2)).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE event_id = ?",
                Int::class.javaObjectType,
                eventId,
            ),
        ).isEqualTo(1)
    }

    private fun countFor(entryId: Long): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE waitlist_entry_id = ?",
            Int::class.javaObjectType,
            entryId,
        )!!
}
