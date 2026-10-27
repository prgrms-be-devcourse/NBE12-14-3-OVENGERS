package com.ovengers.slotkey.reservation.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.credit.service.CreditServiceImpl
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.outbox.OutboxEventRepository
import com.ovengers.slotkey.outbox.SlotReleasedRecorder
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.eqNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.UUID

class OutboxRecordIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired private lateinit var cancelService: ReservationCancelService

    @Autowired private lateinit var adminService: AdminReservationService

    @Autowired private lateinit var expirationService: ReservationHoldExpirationService

    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var recorder: SlotReleasedRecorder

    @Autowired private lateinit var holdService: ReservationHoldService

    @SpyBean private lateinit var creditService: CreditServiceImpl

    @SpyBean private lateinit var outboxEventRepository: OutboxEventRepository

    @Test
    fun recorderRequiresCallerTransaction() {
        assertThatThrownBy({ recorder.record(1L, 1L, SlotReleasedRecorder.Reason.CANCELLED) })
            .isInstanceOf(IllegalTransactionStateException::class.java)
    }

    @Test
    fun refundFailureRollsBackCancellationWithoutEvent() {
        // 환불은 outbox 기록보다 앞선 단계의 실패다. "이벤트가 없다"만 보여 줄 뿐 outbox 롤백의 증거는 아니다.
        val member: Long = createMember(0)
        val space: Long = createSpace()
        val reservation: Long =
            createReservation(
                member,
                space,
                ReservationStatus.CONFIRMED,
                tomorrowAt(14, 0),
                tomorrowAt(15, 0),
            )
        doThrow(IllegalStateException("refund failure")).`when`(creditService).refund(eqNonNull(member), eqNonNull(reservation), anyInt())
        assertThatThrownBy({ cancelService.cancel(member, reservation) }).isInstanceOf(IllegalStateException::class.java)
        assertThat(events(reservation)).isEmpty()
        assertThat(statusOf(reservation)).isEqualTo("CONFIRMED")
        assertThat(countSlots(reservation)).isEqualTo(2)
    }

    @Test
    fun penaltyFailureRollsBackRefundAndCancellation() {
        // given: 부분 환불 대상 예약
        val member = createMember(3_000)
        val space = createSpace()
        val startTime = now().plusMinutes(30)

        val reservation =
            createReservation(
                member,
                space,
                ReservationStatus.CONFIRMED,
                startTime,
                startTime.plusHours(1),
            )

        val balanceBefore = balanceOf(member)

        // 위약금 처리에서 예외 발생
        doThrow(IllegalStateException("penalty failure"))
            .`when`(creditService)
            .penalize(eqNonNull(member), eqNonNull(reservation), anyInt())

        // when: 취소 요청
        assertThatThrownBy { cancelService.cancel(member, reservation) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("penalty failure")

        // 환불 이후 위약금 처리가 호출됐는지 검증
        val order = inOrder(creditService)
        order.verify(creditService).refund(
            eqNonNull(member),
            eqNonNull(reservation),
            eqNonNull(10_000),
        )
        order.verify(creditService).penalize(
            eqNonNull(member),
            eqNonNull(reservation),
            eqNonNull(5_000),
        )

        // then: 전체 트랜잭션 롤백 검증
        assertThat(statusOf(reservation)).isEqualTo("CONFIRMED")
        assertThat(countSlots(reservation)).isEqualTo(2)
        assertThat(balanceOf(member)).isEqualTo(balanceBefore)
        assertThat(countLedger(reservation, "REFUND")).isZero()
        assertThat(countLedger(reservation, "PENALTY")).isZero()
        assertThat(countHistory(reservation, "CANCELLED")).isZero()
        assertThat(events(reservation)).isEmpty()
    }

    @Test
    fun cancellationRecordsOnePendingEvent() {
        val member: Long = createMember(0)
        val space: Long = createSpace()
        val reservation: Long =
            createReservation(
                member,
                space,
                ReservationStatus.CONFIRMED,
                tomorrowAt(14, 0),
                tomorrowAt(15, 0),
            )
        cancelService.cancel(member, reservation)
        assertEvent(reservation, space, "CANCELLED")
    }

    @Test
    fun heldCancellationReleasesSlotsWithoutRefundAndRecordsOneEvent() {
        val member: Long = createMember(0)
        val space: Long = createSpace()
        val reservation: Long =
            createReservation(
                member,
                space,
                ReservationStatus.HELD,
                tomorrowAt(14, 0),
                tomorrowAt(15, 0),
            )

        cancelService.cancel(member, reservation)

        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM reservation WHERE id = ?",
                String::class.java,
                reservation,
            ),
        ).isEqualTo("CANCELLED")
        assertThat(countSlots(reservation)).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_transaction WHERE member_id = ?",
                Int::class.javaObjectType,
                member,
            ),
        ).isZero()
        assertEvent(reservation, space, "CANCELLED")
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus::class, names = ["CONFIRMED", "HELD"])
    fun forceCancellationRecordsOneEvent(status: ReservationStatus) {
        val member: Long = createMember(0)
        val admin: Long = createMember(0)
        val space: Long = createSpace()
        val reservation: Long = createReservation(member, space, status, tomorrowAt(14, 0), tomorrowAt(15, 0))
        adminService.forceCancel(reservation, "test", admin)
        assertEvent(reservation, space, "FORCE_CANCELLED")
    }

    @Test
    fun forceCancellationOfInUseReservationRecordsOneEvent() {
        val member: Long = createMember(0)
        val admin: Long = createMember(0)
        val space: Long = createSpace()
        val reservation: Long =
            createReservation(
                member,
                space,
                ReservationStatus.CONFIRMED,
                tomorrowAt(14, 0),
                tomorrowAt(15, 0),
            )
        // chk_reservation_checked_in 제약 때문에 IN_USE 행은 checked_in_at과 함께 전이시켜야 한다.
        jdbcTemplate.update(
            "UPDATE reservation SET status = 'IN_USE', checked_in_at = ? WHERE id = ?",
            now(),
            reservation,
        )
        adminService.forceCancel(reservation, "test", admin)
        assertThat(statusOf(reservation)).isEqualTo("CANCELLED")
        assertEvent(reservation, space, "FORCE_CANCELLED")
    }

    @Test
    fun expirationRecordsOnlyOnSuccessfulTransition() {
        val member: Long = createMember(0)
        val space: Long = createSpace()
        val reservation: Long =
            createReservation(
                member,
                space,
                ReservationStatus.HELD,
                tomorrowAt(14, 0),
                tomorrowAt(15, 0),
            )
        jdbcTemplate.update("UPDATE reservation SET hold_expires_at = ? WHERE id = ?", now().minusMinutes(1), reservation)
        transactionTemplate.executeWithoutResult { tx -> assertThat(expirationService.expireHold(reservation, now())).isTrue() }
        assertEvent(reservation, space, "HOLD_EXPIRED")
        transactionTemplate.executeWithoutResult { tx -> assertThat(expirationService.expireHold(reservation, now())).isFalse() }
        assertThat(events(reservation)).hasSize(1)
    }

    @Test
    fun alreadyExpiredReservationRecordsNothing() {
        val member: Long = createMember(0)
        val space: Long = createSpace()
        val reservation: Long =
            createReservation(
                member,
                space,
                ReservationStatus.EXPIRED,
                tomorrowAt(14, 0),
                tomorrowAt(15, 0),
            )
        transactionTemplate.executeWithoutResult { tx -> assertThat(expirationService.expireHold(reservation, now())).isFalse() }
        assertThat(events(reservation)).isEmpty()
    }

    @Test
    fun callerRollbackRemovesEventAndRestoresSlots() {
        val member: Long = createMember(0)
        val space: Long = createSpace()
        val reservation: Long =
            createReservation(
                member,
                space,
                ReservationStatus.CONFIRMED,
                tomorrowAt(14, 0),
                tomorrowAt(15, 0),
            )
        assertThatThrownBy({
            transactionTemplate.executeWithoutResult { tx ->
                cancelService.cancel(member, reservation)
                assertThat(events(reservation)).hasSize(1)
                throw IllegalStateException("rollback after outbox insert")
            }
        }).isInstanceOf(IllegalStateException::class.java)
        assertThat(events(reservation)).isEmpty()
        assertThat(statusOf(reservation)).isEqualTo("CONFIRMED")
        assertThat(countSlots(reservation)).isEqualTo(2)
        assertThat(countLedger(reservation, "REFUND")).isZero()
    }

    @Test
    fun recorderFailureRollsBackCancellation() {
        val member: Long = createMember(0)
        val space: Long = createSpace()
        val reservation: Long =
            createReservation(
                member,
                space,
                ReservationStatus.CONFIRMED,
                tomorrowAt(14, 0),
                tomorrowAt(15, 0),
            )
        val balanceBefore: Int = balanceOf(member)
        doThrow(IllegalStateException("outbox insert failure")).`when`(outboxEventRepository).save(anyNonNull())

        assertThatThrownBy({ cancelService.cancel(member, reservation) })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("outbox insert failure")

        assertThat(events(reservation)).isEmpty()
        assertThat(statusOf(reservation)).isEqualTo("CONFIRMED")
        assertThat(countSlots(reservation)).isEqualTo(2)
        assertThat(countLedger(reservation, "REFUND")).isZero()
        assertThat(countHistory(reservation, "CANCELLED")).isZero()
        assertThat(balanceOf(member)).isEqualTo(balanceBefore)
    }

    @Test
    fun recorderFailureRollsBackForceCancellation() {
        val member: Long = createMember(0)
        val admin: Long = createMember(0)
        val space: Long = createSpace()
        val reservation: Long =
            createReservation(
                member,
                space,
                ReservationStatus.CONFIRMED,
                tomorrowAt(14, 0),
                tomorrowAt(15, 0),
            )
        doThrow(IllegalStateException("outbox insert failure")).`when`(outboxEventRepository).save(anyNonNull())

        assertThatThrownBy({ adminService.forceCancel(reservation, "test", admin) })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("outbox insert failure")

        assertThat(events(reservation)).isEmpty()
        assertThat(statusOf(reservation)).isEqualTo("CONFIRMED")
        assertThat(countSlots(reservation)).isEqualTo(2)
        assertThat(countLedger(reservation, "REFUND")).isZero()
        assertThat(countHistory(reservation, "CANCELLED")).isZero()
    }

    @Test
    fun holdCreationExpiringOtherMembersHoldRecordsOneEventAndCommitsNewSlots() {
        val owner: Long = createMember(0)
        val other: Long = createMember(0)
        val space: Long = createSpace()
        val expired: Long = createReservation(owner, space, ReservationStatus.HELD, tomorrowAt(14, 0), tomorrowAt(15, 0))
        jdbcTemplate.update("UPDATE reservation SET hold_expires_at = ? WHERE id = ?", now().minusMinutes(1), expired)

        val created: ReservationResponse = holdService.createHold(other, space, tomorrowAt(14, 0), tomorrowAt(15, 0))

        assertThat(statusOf(expired)).isEqualTo("EXPIRED")
        assertThat(countSlots(expired)).isZero()
        assertEvent(expired, space, "HOLD_EXPIRED")
        assertThat(statusOf(created.reservationId)).isEqualTo("HELD")
        assertThat(countSlots(created.reservationId)).isEqualTo(2)
        assertThat(events(created.reservationId)).isEmpty()
    }

    @Test
    fun slotConflictAfterExpiringHoldRollsBackEverythingWithoutEvent() {
        val owner: Long = createMember(0)
        val other: Long = createMember(0)
        val space: Long = createSpace()
        // 14:00~14:30은 살아있는 CONFIRMED, 14:30~15:00은 만료된 HELD. 14:00~15:00 HOLD는 만료 정리 뒤 슬롯 INSERT에서 충돌한다.
        createReservation(owner, space, ReservationStatus.CONFIRMED, tomorrowAt(14, 0), tomorrowAt(14, 30))
        val expired: Long = createReservation(owner, space, ReservationStatus.HELD, tomorrowAt(14, 30), tomorrowAt(15, 0))
        jdbcTemplate.update("UPDATE reservation SET hold_expires_at = ? WHERE id = ?", now().minusMinutes(1), expired)
        val reservationsBefore: Int = countReservations(space)

        assertThatThrownBy({ holdService.createHold(other, space, tomorrowAt(14, 0), tomorrowAt(15, 0)) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_SLOT_CONFLICT)

        assertThat(events(expired)).isEmpty()
        assertThat(statusOf(expired)).isEqualTo("HELD")
        assertThat(countSlots(expired)).isEqualTo(1)
        assertThat(countReservations(space)).isEqualTo(reservationsBefore)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE partition_key = ?",
                Int::class.javaObjectType,
                space.toString(),
            ),
        ).isZero()
    }

    private fun countReservations(space: Long): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reservation WHERE space_id = ?",
            Int::class.javaObjectType,
            space,
        )!!

    private fun events(reservation: Long): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("SELECT * FROM outbox_event WHERE JSON_EXTRACT(payload, '\$.reservationId') = ?", reservation)

    private fun assertEvent(
        reservation: Long,
        space: Long,
        reason: String,
    ) {
        val rows: List<Map<String, Any?>> = events(reservation)
        assertThat(rows).hasSize(1)
        val row: Map<String, Any?> = rows.first()
        assertThat(row.get("event_type")).isEqualTo("SlotReleased")
        assertThat(row.get("topic")).isEqualTo("slot-released")
        assertThat(row.get("partition_key")).isEqualTo(space.toString())
        assertThat(row.get("status")).isEqualTo("PENDING")
        assertThat((row.get("attempts") as Number).toInt()).isZero()
        assertThat(row.get("published_at")).isNull()
        val payload: JsonNode = objectMapper.readTree(row.get("payload").toString())
        assertThat(payload.size()).isEqualTo(4)
        assertThat(payload.get("eventId").asText()).isEqualTo(row.get("event_id"))
        assertThat(UUID.fromString(payload.get("eventId").asText())).isNotNull()
        assertThat(payload.get("reservationId").asLong()).isEqualTo(reservation)
        assertThat(payload.get("reason").asText()).isEqualTo(reason)
        val occurredAt: LocalDateTime = LocalDateTime.parse(payload.get("occurredAt").asText())
        assertThat(occurredAt).isBetween(LocalDateTime.now(clock).minusMinutes(1), LocalDateTime.now(clock))
        val createdAt: LocalDateTime? =
            jdbcTemplate.queryForObject(
                "SELECT created_at FROM outbox_event WHERE event_id = ?",
                LocalDateTime::class.java,
                row.get("event_id"),
            )
        assertThat(createdAt).isBetween(occurredAt.minusNanos(1000), occurredAt.plusNanos(1000))
    }
}
