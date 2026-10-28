package com.ovengers.slotkey.notification

import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.notification.consumer.ProcessedEventStore
import com.ovengers.slotkey.notification.consumer.SlotReleasedHandler
import com.ovengers.slotkey.notification.repository.NotificationRepository
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.waitlist.entity.WaitlistEntry
import com.ovengers.slotkey.waitlist.repository.WaitlistEntryRepository
import com.ovengers.slotkey.waitlist.service.ReservationOccupancyProvider
import com.ovengers.slotkey.waitlist.service.ReservationRangeProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

/**
 * SlotReleased 컨슈머 핸들러의 멱등(4-2)과 알림 대상 선정(4-3)을 실제 MySQL로 검증한다.
 * 이벤트 payload에는 구간이 없으므로 핸들러는 reservationId로 예약 행을 읽어 해제 구간을 얻는다.
 * "해제"는 예약을 CANCELLED로 바꾸고 슬롯을 지우는 것으로 흉내 낸다(취소 서비스가 하는 일).
 */
class SlotReleasedHandlerTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var handler: SlotReleasedHandler

    @Autowired
    private lateinit var waitlistEntryRepository: WaitlistEntryRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var processedEventStore: ProcessedEventStore

    @Autowired
    private lateinit var reservationRangeProvider: ReservationRangeProvider

    @Autowired
    private lateinit var reservationOccupancyProvider: ReservationOccupancyProvider

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    private var spaceId: Long = 0L
    private var occupierId: Long = 0L
    private var releasedReservationId: Long = 0L
    private lateinit var start: LocalDateTime
    private lateinit var end: LocalDateTime
    private lateinit var eventId: String

    @BeforeEach
    fun setUp() {
        spaceId = createSpace()
        occupierId = createMember(0)
        start = tomorrowAt(14, 0)
        end = tomorrowAt(15, 0)
        releasedReservationId = createReservation(occupierId, spaceId, ReservationStatus.CONFIRMED, start, end)
        release(releasedReservationId)
        eventId = UUID.randomUUID().toString()
    }

    @AfterEach
    fun cleanProcessedEvents() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE event_id = ?", eventId)
    }

    // ---------- 4-2 멱등 ----------

    @Test
    @DisplayName("같은 이벤트를 두 번 전달해도 알림은 한 번만 생기고 처리 기록도 한 건이다")
    fun handle_sameEventTwice_notifiesOnce() {
        val entryId = waiting(createMember(0), spaceId, start, end)

        handler.handle(eventId, releasedReservationId)
        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(entryId)).isEqualTo(1)
        assertThat(processedCount()).isEqualTo(1)
    }

    @Test
    @DisplayName("알림 저장이 실패하면 처리 기록도 함께 롤백되어 재전달 때 다시 처리된다")
    fun handle_whenNotificationFails_rollsBackProcessedRecord() {
        val memberId = createMember(0)
        val entryId = waiting(memberId, spaceId, start, end)
        // (event_id, waitlist_entry_id) UNIQUE를 일부러 선점해 알림 INSERT가 실패하게 만든다.
        jdbcTemplate.update(
            "INSERT INTO notification (member_id, waitlist_entry_id, reference_id, event_id, message, created_at) " +
                "VALUES (?, ?, ?, ?, 'x', NOW())",
            memberId,
            entryId,
            entryId,
            eventId,
        )

        try {
            handler.handle(eventId, releasedReservationId)
        } catch (expected: RuntimeException) {
            // 중복 알림 INSERT 실패
        }

        assertThat(processedCount()).isZero()
    }

    @Test
    @DisplayName("존재하지 않는 예약 id의 이벤트는 알림 없이 조용히 끝난다")
    fun handle_unknownReservation_doesNothing() {
        val entryId = waiting(createMember(0), spaceId, start, end)

        handler.handle(eventId, -1L)

        assertThat(notificationCount(entryId)).isZero()
    }

    // ---------- 4-3 대상 선정 ----------

    @Test
    @DisplayName("구간 전체가 비어 있는 WAITING 대기자 전원에게 알림이 가고, 항목은 WAITING을 유지한 채 알림 시각이 기록된다")
    fun handle_allEligibleWaiters_areNotified() {
        val entry1 = waiting(createMember(0), spaceId, start, end)
        val entry2 = waiting(createMember(0), spaceId, start, end)

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(entry1)).isEqualTo(1)
        assertThat(notificationCount(entry2)).isEqualTo(1)
        assertThat(statusOfEntry(entry1)).isEqualTo("WAITING")
        assertThat(lastNotifiedAtIsSet(entry1)).isTrue()
    }

    @Test
    @DisplayName("알림 문구에 선착순이며 자리가 보장되지 않는다는 점이 들어 있다")
    fun handle_message_statesFirstComeFirstServed() {
        val entryId = waiting(createMember(0), spaceId, start, end)

        handler.handle(eventId, releasedReservationId)

        val message =
            jdbcTemplate.queryForObject(
                "SELECT message FROM notification WHERE waitlist_entry_id = ?",
                String::class.java,
                entryId,
            )
        assertThat(message).contains("선착순").contains("보장")
    }

    @Test
    @DisplayName("대기 구간의 일부 슬롯만 비었으면(나머지는 점유 중) 알림이 가지 않는다")
    fun handle_partiallyFreeRange_isExcluded() {
        // 대기 구간 14:00~16:00 중 해제된 14:00~15:00만 비었고 15:00~15:30은 다른 사람이 점유 중이다.
        createReservation(
            createMember(0),
            spaceId,
            ReservationStatus.CONFIRMED,
            tomorrowAt(15, 0),
            tomorrowAt(15, 30),
        )
        val entryId = waiting(createMember(0), spaceId, start, tomorrowAt(16, 0))

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(entryId)).isZero()
    }

    @Test
    @DisplayName("이벤트 이후 다른 사람이 해제된 슬롯을 다시 점유(HELD)했으면 알림이 가지 않는다")
    fun handle_slotReoccupiedAfterRelease_isExcluded() {
        createReservation(createMember(0), spaceId, ReservationStatus.HELD, start, end)
        val entryId = waiting(createMember(0), spaceId, start, end)

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(entryId)).isZero()
    }

    @Test
    @DisplayName("재점유된 HELD가 이미 만료됐다면 점유가 아니므로 알림이 간다")
    fun handle_reoccupiedButExpiredHold_isNotified() {
        val heldId = createReservation(createMember(0), spaceId, ReservationStatus.HELD, start, end)
        jdbcTemplate.update("UPDATE reservation SET hold_expires_at = ? WHERE id = ?", now().minusMinutes(1), heldId)
        val entryId = waiting(createMember(0), spaceId, start, end)

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(entryId)).isEqualTo(1)
    }

    @Test
    @DisplayName("정지 회원은 알림이 가지 않고 항목은 WAITING으로 유지된다")
    fun handle_suspendedMember_isExcluded() {
        val memberId = createMember(0)
        jdbcTemplate.update("UPDATE member SET status = 'SUSPENDED' WHERE id = ?", memberId)
        val entryId = waiting(memberId, spaceId, start, end)

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(entryId)).isZero()
        assertThat(statusOfEntry(entryId)).isEqualTo("WAITING")
    }

    @Test
    @DisplayName("탈퇴 회원은 알림이 가지 않는다")
    fun handle_withdrawnMember_isExcluded() {
        val memberId = createMember(0)
        jdbcTemplate.update("UPDATE member SET status = 'WITHDRAWN' WHERE id = ?", memberId)
        val entryId = waiting(memberId, spaceId, start, end)

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(entryId)).isZero()
    }

    @Test
    @DisplayName("시작 시간이 이미 지난 대기 항목은 알림이 가지 않는다")
    fun handle_startAlreadyPassed_isExcluded() {
        val pastStart = now().minusHours(3).withMinute(0).withSecond(0)
        val pastEnd = pastStart.plusHours(1)
        val pastReservation = createReservation(occupierId, spaceId, ReservationStatus.CONFIRMED, pastStart, pastEnd)
        release(pastReservation)
        val entryId = waiting(createMember(0), spaceId, pastStart, pastEnd)

        handler.handle(eventId, pastReservation)

        assertThat(notificationCount(entryId)).isZero()
    }

    @Test
    @DisplayName("시작 시각이 핸들러의 now와 정확히 같은 항목은 제외되고, now보다 1초 뒤면 알림이 간다")
    fun handle_startBoundary_usesStrictlyFuture() {
        // 핸들러의 Clock을 고정해 "시작 == now"를 정확히 만든다(실시간 Clock이면 now()보다 약간 과거만 검사하게 된다).
        val boundary =
            now()
                .plusDays(1)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
        val fixed = Clock.fixed(boundary.atZone(ClockConfig.DEFAULT_ZONE).toInstant(), ClockConfig.DEFAULT_ZONE)
        val fixedClockHandler =
            SlotReleasedHandler(
                processedEventStore,
                reservationRangeProvider,
                reservationOccupancyProvider,
                waitlistEntryRepository,
                memberRepository,
                spaceRepository,
                notificationRepository,
                fixed,
            )

        val reservationId =
            createReservation(
                occupierId,
                spaceId,
                ReservationStatus.CONFIRMED,
                boundary,
                boundary.plusHours(2),
            )
        release(reservationId)
        val atNow = waiting(createMember(0), spaceId, boundary, boundary.plusHours(1))
        val afterNow = waiting(createMember(0), spaceId, boundary.plusSeconds(1), boundary.plusHours(1))

        // 직접 만든 인스턴스에는 @Transactional 프록시가 없으므로 트랜잭션을 직접 연다.
        TransactionTemplate(transactionManager).executeWithoutResult(
            { _ -> fixedClockHandler.handle(eventId, reservationId) },
        )

        assertThat(notificationCount(atNow)).isZero()
        assertThat(notificationCount(afterNow)).isEqualTo(1)
    }

    @Test
    @DisplayName("본인이 취소한(CANCELLED) 대기 항목은 알림이 가지 않는다")
    fun handle_cancelledEntry_isExcluded() {
        val entry = WaitlistEntry.waiting(createMember(0), spaceId, start, end, now())
        entry.cancel()
        val entryId = waitlistEntryRepository.saveAndFlush(entry).id!!

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(entryId)).isZero()
    }

    @Test
    @DisplayName("신청 뒤 본인이 같은 공간·겹치는 시간에 CONFIRMED 예약을 잡았으면 알림이 가지 않는다")
    fun handle_memberNowHoldsOwnReservation_isExcluded() {
        val memberId = createMember(0)
        val entryId = waiting(memberId, spaceId, start, end)
        val own = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, start, end)
        // 슬롯 점유 검사와 독립적으로 이 규칙만 검증하기 위해 슬롯을 지운다.
        jdbcTemplate.update("DELETE FROM reservation_slot WHERE reservation_id = ?", own)

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(entryId)).isZero()
    }

    @Test
    @DisplayName("신청 뒤 본인이 만료 전 HELD 예약을 잡았으면 알림이 가지 않는다")
    fun handle_memberNowHoldsOwnHeld_isExcluded() {
        val memberId = createMember(0)
        val entryId = waiting(memberId, spaceId, start, end)
        val own = createReservation(memberId, spaceId, ReservationStatus.HELD, start, end)
        jdbcTemplate.update("DELETE FROM reservation_slot WHERE reservation_id = ?", own)

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(entryId)).isZero()
    }

    @Test
    @DisplayName("본인 예약이 다른 공간이거나 겹치지 않는 시간이면 알림이 간다")
    fun handle_memberHasUnrelatedReservation_isNotified() {
        val memberId = createMember(0)
        val entryId = waiting(memberId, spaceId, start, end)
        createReservation(memberId, createSpace(), ReservationStatus.CONFIRMED, start, end)
        createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, tomorrowAt(18, 0), tomorrowAt(19, 0))

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(entryId)).isEqualTo(1)
    }

    @Test
    @DisplayName("다른 공간의 대기, 해제 구간과 겹치지 않는 대기에는 알림이 가지 않는다")
    fun handle_otherSpaceOrNonOverlappingRange_isExcluded() {
        val otherSpace = createSpace()
        val otherSpaceEntry = waiting(createMember(0), otherSpace, start, end)
        val laterEntry = waiting(createMember(0), spaceId, tomorrowAt(16, 0), tomorrowAt(17, 0))

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(otherSpaceEntry)).isZero()
        assertThat(notificationCount(laterEntry)).isZero()
    }

    @Test
    @DisplayName("대기 구간이 해제 구간보다 넓어도(나머지도 비어 있으면) 알림이 가고, 일부만 겹쳐도 가능하다")
    fun handle_widerOrPartiallyOverlappingRange_isNotifiedWhenAllFree() {
        val wider = waiting(createMember(0), spaceId, tomorrowAt(13, 0), tomorrowAt(16, 0))
        val partial = waiting(createMember(0), spaceId, tomorrowAt(14, 30), tomorrowAt(15, 30))

        handler.handle(eventId, releasedReservationId)

        assertThat(notificationCount(wider)).isEqualTo(1)
        assertThat(notificationCount(partial)).isEqualTo(1)
    }

    @Test
    @DisplayName("같은 대기 항목에 서로 다른 이벤트가 두 번 오면 알림이 2건이고 알림 시각이 갱신된다")
    fun handle_twoDifferentEventsForSameEntry_notifiesTwice() {
        val entryId = waiting(createMember(0), spaceId, start, end)
        val secondEventId = UUID.randomUUID().toString()
        try {
            handler.handle(eventId, releasedReservationId)
            val first = lastNotifiedAt(entryId)
            jdbcTemplate.update(
                "UPDATE waitlist_entry SET last_notified_at = ? WHERE id = ?",
                first.minusHours(1),
                entryId,
            )

            handler.handle(secondEventId, releasedReservationId)

            assertThat(notificationCount(entryId)).isEqualTo(2)
            assertThat(lastNotifiedAt(entryId)).isAfter(first.minusHours(1))
        } finally {
            jdbcTemplate.update("DELETE FROM processed_event WHERE event_id = ?", secondEventId)
        }
    }

    @Test
    @DisplayName("한 건이라도 알림 저장이 실패하면 같은 이벤트의 앞선 알림·알림 시각·처리 기록이 모두 롤백된다")
    fun handle_failureOnLaterCandidate_rollsBackEverything() {
        val okMember = createMember(0)
        val conflictMember = createMember(0)
        val okEntry = waiting(okMember, spaceId, start, end)
        val conflictEntry = waiting(conflictMember, spaceId, start, end)
        // 나중 후보(id가 큰 쪽)의 (event_id, waitlist_entry_id)를 선점해 두 번째 알림 INSERT를 실패시킨다.
        jdbcTemplate.update(
            "INSERT INTO notification (member_id, waitlist_entry_id, reference_id, event_id, message, created_at) " +
                "VALUES (?, ?, ?, ?, 'x', NOW())",
            conflictMember,
            conflictEntry,
            conflictEntry,
            eventId,
        )

        try {
            handler.handle(eventId, releasedReservationId)
        } catch (expected: RuntimeException) {
            // 두 번째 후보의 중복 알림 INSERT 실패
        }

        assertThat(notificationCount(okEntry)).isZero()
        assertThat(lastNotifiedAtIsSet(okEntry)).isFalse()
        assertThat(processedCount()).isZero()
    }

    @Test
    @DisplayName("공간 이름이 최대 길이(100자)여도 알림 문구가 컬럼 한도(500자) 안에 들어간다")
    fun handle_longestSpaceName_messageFitsColumn() {
        jdbcTemplate.update("UPDATE spaces SET name = ? WHERE id = ?", "가".repeat(100), spaceId)
        val entryId = waiting(createMember(0), spaceId, start, end)

        handler.handle(eventId, releasedReservationId)

        val message =
            jdbcTemplate.queryForObject(
                "SELECT message FROM notification WHERE waitlist_entry_id = ?",
                String::class.java,
                entryId,
            )
        assertThat(message).contains("가".repeat(100)).hasSizeLessThanOrEqualTo(500)
    }

    // ---------- 헬퍼 ----------

    private fun waiting(
        memberId: Long,
        space: Long,
        from: LocalDateTime,
        to: LocalDateTime,
    ): Long = waitlistEntryRepository.saveAndFlush(WaitlistEntry.waiting(memberId, space, from, to, now())).id!!

    private fun release(reservationId: Long) {
        jdbcTemplate.update("UPDATE reservation SET status = 'CANCELLED', cancelled_at = NOW() WHERE id = ?", reservationId)
        jdbcTemplate.update("DELETE FROM reservation_slot WHERE reservation_id = ?", reservationId)
    }

    private fun notificationCount(entryId: Long): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE waitlist_entry_id = ?",
            Int::class.javaObjectType,
            entryId,
        )

    private fun processedCount(): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM processed_event WHERE event_id = ?",
            Int::class.javaObjectType,
            eventId,
        )

    private fun statusOfEntry(entryId: Long): String =
        jdbcTemplate.queryForObject("SELECT status FROM waitlist_entry WHERE id = ?", String::class.java, entryId)

    private fun lastNotifiedAt(entryId: Long): LocalDateTime =
        jdbcTemplate.queryForObject(
            "SELECT last_notified_at FROM waitlist_entry WHERE id = ?",
            LocalDateTime::class.java,
            entryId,
        )

    private fun lastNotifiedAtIsSet(entryId: Long): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT last_notified_at IS NOT NULL FROM waitlist_entry WHERE id = ?",
            Boolean::class.javaObjectType,
            entryId,
        )
}
