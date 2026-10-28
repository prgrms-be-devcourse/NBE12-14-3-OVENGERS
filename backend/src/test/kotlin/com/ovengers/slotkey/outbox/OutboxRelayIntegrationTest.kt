package com.ovengers.slotkey.outbox

import com.ovengers.slotkey.notification.consumer.NotificationEventListener
import com.ovengers.slotkey.notification.entity.NotificationType
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.service.ReservationCancelService
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple.tuple
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestPropertySource(
    properties = [
        "slotkey.kafka.enabled=true",
        "slotkey.outbox.relay.batch-size=2",
        "slotkey.outbox.relay.fixed-delay-ms=3600000",
        "slotkey.outbox.relay.initial-delay-ms=3600000",
    ],
)
@Import(OutboxRelayIntegrationTest.FakeConfig::class)
class OutboxRelayIntegrationTest : ReservationIntegrationTestSupport() {
    @TestConfiguration
    class FakeConfig {
        @Bean
        @Primary
        fun fakeOutboxPublisher(): FakeOutboxPublisher = FakeOutboxPublisher()
    }

    @Autowired
    private lateinit var relay: OutboxRelay

    @Autowired
    private lateinit var fake: FakeOutboxPublisher

    @Autowired
    private lateinit var outboxRepository: OutboxEventRepository

    @Autowired
    private lateinit var cancelService: ReservationCancelService

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var notificationRecorder: NotificationEventRecorder

    @Autowired
    private lateinit var notificationListener: NotificationEventListener

    private val key = "relay-" + System.nanoTime()

    @BeforeEach
    fun clean() {
        // 앞선 테스트가 남긴 PENDING이 이 테스트의 relay 배치를 차지하지 않게 한다.
        jdbcTemplate.update("DELETE FROM outbox_event")
        fake.reset()
    }

    @AfterEach
    fun removeSeeds() {
        jdbcTemplate.update("DELETE FROM outbox_event WHERE partition_key = ?", key)
    }

    @Test
    fun enabledContextStartsWithoutBrokerAndHasRelayAndKafkaPublisher() {
        assertThat(context.getBeanNamesForType(OutboxRelay::class.java)).hasSize(1)
        assertThat(context.getBeanNamesForType(KafkaOutboxPublisher::class.java)).hasSize(1)
    }

    @Test
    fun successMarksPublishedWithTimestampUsingRowTopicAndKey() {
        val id = seed("a")

        relay.relayPending()

        assertThat(fake.sent())
            .extracting(FakeOutboxPublisher.Sent::topic, FakeOutboxPublisher.Sent::key)
            .containsExactly(tuple("slot-released", key))
        assertThat(statusOfEvent(id)).isEqualTo("PUBLISHED")
        assertThat(publishedAt(id)).isNotNull()
        assertThat(attempts(id)).isZero()
    }

    @Test
    fun notificationEventTravelsFromRecorderThroughRelayAndListenerToOneNotification() {
        val member = createMember(0)
        transactionTemplate.executeWithoutResult {
            notificationRecorder.record(NotificationType.CREDIT_GRANTED, member, 7L, "크레딧이 지급되었습니다.")
        }

        relay.relayPending()

        val sent = fake.sent().single()
        assertThat(sent.topic).isEqualTo("notification-events")
        assertThat(sent.key).isEqualTo(member.toString())
        // 브로커 없이 relay가 내보낸 payload를 그대로 컨슈머에 두 번 전달한다(최소 1회 전달 → 중복 흡수).
        notificationListener.onMessage(sent.payload)
        notificationListener.onMessage(sent.payload)
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE member_id = ?", Int::class.javaObjectType, member))
            .isEqualTo(1)
        jdbcTemplate.update(
            "DELETE FROM processed_event WHERE consumer = 'notification-notifier' AND event_id IN (SELECT event_id FROM outbox_event WHERE partition_key = ?)",
            member.toString(),
        )
    }

    @Test
    fun failureRaisesAttemptsAndContinuesWithNextRow() {
        val first = seed("fail-me")
        val second = seed("ok")
        fake.failWhen { payload -> payload.contains("fail-me") }

        relay.relayPending()

        assertThat(statusOfEvent(first)).isEqualTo("PENDING")
        assertThat(attempts(first)).isEqualTo(1)
        assertThat(publishedAt(first)).isNull()
        assertThat(statusOfEvent(second)).isEqualTo("PUBLISHED")

        fake.failWhen { false }
        relay.relayPending()

        assertThat(statusOfEvent(first)).isEqualTo("PUBLISHED")
        assertThat(attempts(first)).isEqualTo(1)
    }

    @Test
    fun processesAtMostBatchSizeRowsInIdOrder() {
        val a = seed("a")
        val b = seed("b")
        val c = seed("c")

        relay.relayPending()

        assertThat(statusOfEvent(a)).isEqualTo("PUBLISHED")
        assertThat(statusOfEvent(b)).isEqualTo("PUBLISHED")
        assertThat(statusOfEvent(c)).isEqualTo("PENDING")
    }

    @Test
    fun publishedRowsAreNotPickedUpAgain() {
        seed("a")

        relay.relayPending()
        relay.relayPending()

        assertThat(fake.sent()).hasSize(1)
    }

    @Test
    fun conditionalUpdateAppliesOnlyOnce() {
        val id = seed("a")
        val t1 = now()

        assertThat(outboxRepository.markPublished(id, t1)).isEqualTo(1)
        assertThat(outboxRepository.markPublished(id, t1.plusHours(1))).isZero()
        assertThat(outboxRepository.increaseAttempts(id)).isZero()

        assertThat(publishedAt(id)).isEqualTo(t1)
        assertThat(attempts(id)).isZero()
    }

    /**
     * relay가 도는 동안 취소 트랜잭션이 열려 있어도(outbox INSERT를 이미 한 상태) 서로 기다리지 않아야 한다.
     * 취소 트랜잭션을 커밋 직전에 붙잡아 두고 그 사이 relay 한 번을 끝까지 돌려 실제로 겹치게 한다.
     * relay가 (status, id) 범위를 FOR UPDATE로 잠그면 여기서 relay가 타임아웃으로 실패한다.
     */
    @Test
    fun relayDoesNotBlockAnOpenCancelTransaction() {
        val seeded = seed("seeded")
        val member = createMember(0)
        val space = createSpace()
        val reservation =
            createReservation(
                member,
                space,
                ReservationStatus.CONFIRMED,
                tomorrowAt(14, 0),
                tomorrowAt(15, 0),
            )

        val cancelInserted = CountDownLatch(1)
        val relayFinished = CountDownLatch(1)
        val cancel =
            CompletableFuture.runAsync {
                transactionTemplate.executeWithoutResult {
                    cancelService.cancel(member, reservation)
                    cancelInserted.countDown()
                    await(relayFinished)
                }
            }
        assertThat(cancelInserted.await(10, TimeUnit.SECONDS)).isTrue()

        try {
            CompletableFuture.runAsync(relay::relayPending).get(5, TimeUnit.SECONDS)
            assertThat(statusOfEvent(seeded)).isEqualTo("PUBLISHED")
        } finally {
            relayFinished.countDown()
        }
        cancel.get(10, TimeUnit.SECONDS)

        assertThat(statusOf(reservation)).isEqualTo("CANCELLED")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_event WHERE partition_key = ?",
                String::class.java,
                space.toString(),
            ),
        ).isEqualTo("PENDING")
    }

    private fun seed(tag: String): Long {
        val eventId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            "INSERT INTO outbox_event (event_id, event_type, topic, partition_key, payload, status, attempts, created_at)" +
                " VALUES (?, 'SlotReleased', 'slot-released', ?, ?, 'PENDING', 0, ?)",
            eventId,
            key,
            "{\"tag\":\"" + tag + "\"}",
            now(),
        )
        return jdbcTemplate.queryForObject("SELECT id FROM outbox_event WHERE event_id = ?", Long::class.javaObjectType, eventId)
    }

    private fun statusOfEvent(id: Long): String? =
        jdbcTemplate.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String::class.java, id)

    private fun attempts(id: Long): Int =
        jdbcTemplate.queryForObject("SELECT attempts FROM outbox_event WHERE id = ?", Int::class.javaObjectType, id)

    private fun publishedAt(id: Long): LocalDateTime? =
        jdbcTemplate.queryForObject("SELECT published_at FROM outbox_event WHERE id = ?", LocalDateTime::class.java, id)

    companion object {
        private fun await(latch: CountDownLatch) {
            try {
                latch.await(10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
