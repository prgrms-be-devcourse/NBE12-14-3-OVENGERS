package com.ovengers.slotkey.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.notification.entity.NotificationType
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate

class NotificationEventRecorderIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired private lateinit var recorder: NotificationEventRecorder

    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @Autowired private lateinit var objectMapper: ObjectMapper

    @AfterEach
    fun cleanOutbox() {
        jdbcTemplate.update("DELETE FROM outbox_event WHERE topic = 'notification-events'")
    }

    @Test
    fun requiresCallerTransaction() {
        assertThatThrownBy { recorder.record(NotificationType.CREDIT_GRANTED, 1L, 2L, "m") }
            .isInstanceOf(IllegalTransactionStateException::class.java)
    }

    @Test
    fun recordsPendingOutboxRowForMemberRecipient() {
        transactionTemplate.executeWithoutResult { recorder.record(NotificationType.CREDIT_GRANTED, 41L, 7L, "크레딧 5,000원이 지급되었습니다.") }

        val rows = jdbcTemplate.queryForList("SELECT * FROM outbox_event WHERE topic = 'notification-events'")
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["event_type"]).isEqualTo("CREDIT_GRANTED")
        assertThat(row["partition_key"]).isEqualTo("41")
        assertThat(row["status"]).isEqualTo("PENDING")
        val payload = objectMapper.readTree(row["payload"] as String)
        assertThat(payload["eventId"].asText()).isEqualTo(row["event_id"])
        assertThat(payload["eventType"].asText()).isEqualTo("CREDIT_GRANTED")
        assertThat(payload["recipientMemberId"].asLong()).isEqualTo(41L)
        assertThat(payload["referenceId"].asLong()).isEqualTo(7L)
        assertThat(payload["message"].asText()).isEqualTo("크레딧 5,000원이 지급되었습니다.")
        assertThat(payload.has("occurredAt")).isTrue()
    }

    @Test
    fun adminBroadcastHasNullRecipientAndFixedPartitionKey() {
        transactionTemplate.executeWithoutResult { recorder.record(NotificationType.INQUIRY_CREATED, null, 9L, "새 문의가 등록되었습니다.") }

        val row = jdbcTemplate.queryForMap("SELECT * FROM outbox_event WHERE topic = 'notification-events'")
        assertThat(row["partition_key"]).isEqualTo("ADMIN")
        assertThat(objectMapper.readTree(row["payload"] as String)["recipientMemberId"].isNull).isTrue()
    }

    @Test
    fun rollbackLeavesNoOutboxRow() {
        assertThatThrownBy {
            transactionTemplate.executeWithoutResult {
                recorder.record(NotificationType.INQUIRY_ANSWERED, 5L, 1L, "m")
                throw IllegalStateException("rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic = 'notification-events'", Int::class.javaObjectType),
        ).isZero()
    }

    @Test
    fun waitlistTypeIsNotARecordableEvent() {
        assertThatThrownBy {
            transactionTemplate.executeWithoutResult { recorder.record(NotificationType.WAITLIST_SLOT_RELEASED, 1L, 1L, "m") }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
