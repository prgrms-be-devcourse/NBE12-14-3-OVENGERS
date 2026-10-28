package com.ovengers.slotkey.credit.webhook

import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.pg.FakePgClient
import com.ovengers.slotkey.credit.pg.PgConfirmationResult
import com.ovengers.slotkey.credit.service.ChargeOrderConfirmService
import com.ovengers.slotkey.credit.service.ChargeOrderConfirmTransactionService
import com.ovengers.slotkey.credit.service.ChargeOrderService
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.LocalDateTime
import java.util.ArrayList
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 5단계 묶음 B: 웹훅 수신 기록을 PG 조회 결과로만 기존 DB 2(completeApproved)에 연결한다.
 */
class PgWebhookEventProcessorIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var processor: PgWebhookEventProcessor

    @Autowired
    private lateinit var eventStore: PgWebhookEventStore

    @Autowired
    private lateinit var confirmTransactionService: ChargeOrderConfirmTransactionService

    @Autowired
    private lateinit var chargeOrderService: ChargeOrderService

    @Autowired
    private lateinit var chargeOrderConfirmService: ChargeOrderConfirmService

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var fakePgClient: FakePgClient

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate
    private var memberId: Long = 0L
    private lateinit var orderId: String
    private lateinit var paymentKey: String

    @BeforeEach
    fun setUp() {
        fakePgClient.reset()
        memberId =
            memberRepository
                .saveAndFlush(
                    Member(
                        "webhook-process-" + UUID.randomUUID() + "@test.com",
                        "웹훅처리회원",
                    ),
                ).id!!
        orderId = chargeOrderService.create(memberId, AMOUNT).orderId
        paymentKey = "payment-" + UUID.randomUUID()
    }

    @AfterEach
    fun cleanUp() {
        fakePgClient.reset()
        jdbcTemplate.update("DELETE FROM pg_webhook_event")
        jdbcTemplate.update("DELETE FROM audit_logs WHERE target_type = 'CHARGE_ORDER'")
        jdbcTemplate.update("DELETE FROM credit_transaction WHERE charge_order_id IS NOT NULL")
        jdbcTemplate.update("DELETE FROM charge_order")
        jdbcTemplate.update("DELETE FROM idempotency_key")
    }

    @Test
    @DisplayName("CONFIRMING 주문의 웹훅은 PG 조회가 승인이면 기존 DB 2로 PAID·TOP_UP 1행·잔액 증가를 반영한다")
    fun process_confirmingAndPgApproved_completesThroughExistingDb2() {
        startConfirmingWithUnknownResult()
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT, "KRW")
        val eventId: Long = insertEvent("forged-payment-key", "DONE")

        processor.process(eventId)

        assertThat(orderStatus()).isEqualTo("PAID")
        assertThat(topUpCount()).isEqualTo(1)
        assertThat(balance()).isEqualTo(AMOUNT)
        assertThat(ledgerSum()).isEqualTo(balance())
        assertThat(idempotencyCount()).isEqualTo(1)
        assertEventDone(eventId, "CONFIRMED")
    }

    @Test
    @DisplayName("본문의 paymentKey가 아니라 주문에 저장된 paymentKey로 PG를 조회하고, 조회는 트랜잭션 밖이다")
    fun process_usesStoredPaymentKeyOutsideTransaction() {
        startConfirmingWithUnknownResult()
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT, "KRW")
        val eventId: Long = insertEvent("forged-payment-key", "DONE")

        processor.process(eventId)

        assertThat(fakePgClient.lookupCalls()).isEqualTo(1)
        assertThat(fakePgClient.lastLookupPaymentKey()).isEqualTo(paymentKey)
        assertThat(fakePgClient.transactionActiveDuringCalls()).containsExactly(false)
        assertThat(fakePgClient.confirmCalls()).isZero()
    }

    @Test
    @DisplayName("본문이 DONE이어도 PG 조회가 승인이 아니면 확정하지 않고 CONFIRMING을 유지한다(FAILED로도 바꾸지 않음)")
    fun process_bodyDoneButPgNotApproved_keepsConfirming() {
        startConfirmingWithUnknownResult()
        val unknownEvent: Long = insertEvent(paymentKey, "DONE")
        fakePgClient.lookupReturnsUnknown()

        processor.process(unknownEvent)

        fakePgClient.lookupReturnsFailed(paymentKey, orderId, AMOUNT, "KRW", "ABORTED")
        val failedEvent: Long = insertEvent(paymentKey, "DONE")
        processor.process(failedEvent)

        assertThat(orderStatus()).isEqualTo("CONFIRMING")
        assertThat(topUpCount()).isZero()
        assertThat(balance()).isZero()
        assertEventDone(unknownEvent, "PG_NOT_APPROVED")
        assertEventDone(failedEvent, "PG_NOT_APPROVED")
    }

    @Test
    @DisplayName("PG 조회 결과가 로컬 주문과 다르면(금액) 확정하지 않는다")
    fun process_pgMismatch_doesNotComplete() {
        startConfirmingWithUnknownResult()
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT + 100, "KRW")
        val eventId: Long = insertEvent(paymentKey, "DONE")

        processor.process(eventId)

        assertThat(orderStatus()).isEqualTo("CONFIRMING")
        assertThat(topUpCount()).isZero()
        assertEventDone(eventId, "PG_MISMATCH")
    }

    @Test
    @DisplayName("PG 조회 예외는 CONFIRMING을 유지하고 행을 처리 완료로 표시한다(복구 스케줄러에 맡김)")
    fun process_pgLookupThrows_keepsConfirming() {
        startConfirmingWithUnknownResult()
        fakePgClient.lookupThrows(IllegalStateException("pg down"))
        val eventId: Long = insertEvent(paymentKey, "DONE")

        processor.process(eventId)

        assertThat(orderStatus()).isEqualTo("CONFIRMING")
        assertThat(topUpCount()).isZero()
        assertEventDone(eventId, "PG_LOOKUP_ERROR")
    }

    @Test
    @DisplayName("이미 PAID인 주문의 지연 웹훅은 PG를 호출하지 않고 충전도 다시 하지 않는다")
    fun process_alreadyPaid_skipsWithoutPgCall() {
        startConfirmingWithUnknownResult()
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT, "KRW")
        processor.process(insertEvent(paymentKey, "DONE"))
        fakePgClient.reset()
        val delayedEvent: Long = insertEvent(paymentKey, "DONE")

        processor.process(delayedEvent)

        assertThat(fakePgClient.lookupCalls()).isZero()
        assertThat(topUpCount()).isEqualTo(1)
        assertThat(balance()).isEqualTo(AMOUNT)
        assertEventDone(delayedEvent, "ALREADY_PAID")
    }

    @Test
    @DisplayName("PENDING 주문의 웹훅은 PG를 호출하지 않고 상태를 바꾸지 않는다")
    fun process_pendingOrder_isNotConfirmed() {
        val eventId: Long = insertEvent("payment-hint", "DONE")

        processor.process(eventId)

        assertThat(orderStatus()).isEqualTo("PENDING")
        assertThat(fakePgClient.lookupCalls()).isZero()
        assertThat(topUpCount()).isZero()
        assertEventDone(eventId, "ORDER_PENDING")
    }

    @Test
    @DisplayName("FAILED 주문의 웹훅은 PG를 호출하지 않고 다시 열지 않는다")
    fun process_failedOrder_isNotReopened() {
        startConfirmingWithUnknownResult()
        jdbcTemplate.update("UPDATE charge_order SET status = 'FAILED' WHERE order_id = ?", orderId)
        val eventId: Long = insertEvent(paymentKey, "DONE")

        processor.process(eventId)

        assertThat(orderStatus()).isEqualTo("FAILED")
        assertThat(fakePgClient.lookupCalls()).isZero()
        assertThat(topUpCount()).isZero()
        assertEventDone(eventId, "ORDER_FAILED")
    }

    @Test
    @DisplayName("CONFIRMING인데 복구 필수값이 없으면 PG를 호출하지 않는다")
    fun process_confirmingWithoutStoredKeys_skips() {
        startConfirmingWithUnknownResult()
        jdbcTemplate.update(
            "UPDATE charge_order SET client_idempotency_key = NULL WHERE order_id = ?",
            orderId,
        )
        val eventId: Long = insertEvent(paymentKey, "DONE")

        processor.process(eventId)

        assertThat(fakePgClient.lookupCalls()).isZero()
        assertThat(orderStatus()).isEqualTo("CONFIRMING")
        assertEventDone(eventId, "ORDER_INVARIANT_VIOLATION")
    }

    @Test
    @DisplayName("PG 승인 조회 뒤 로컬 주문이 FAILED가 되면 덮어쓰지 않고 기존 감사 로그를 남긴다")
    fun process_approvedButLocalFailed_recordsConflict() {
        startConfirmingWithUnknownResult()
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT, "KRW")
        fakePgClient.afterLookupDelay({
            jdbcTemplate.update(
                "UPDATE charge_order SET status = 'FAILED' WHERE order_id = ?",
                orderId,
            )
        })
        val eventId: Long = insertEvent(paymentKey, "DONE")

        processor.process(eventId)

        assertThat(orderStatus()).isEqualTo("FAILED")
        assertThat(topUpCount()).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE target_type = 'CHARGE_ORDER' " +
                    "AND action = 'CHARGE_RECOVERY_MANUAL_REVIEW'",
                Int::class.javaObjectType,
            ),
        ).isEqualTo(1)
        assertEventDone(eventId, "STATE_CONFLICT")
    }

    @Test
    @DisplayName("이미 처리한 행을 다시 처리해도 PG를 부르지 않고 결과를 덮어쓰지 않는다")
    fun process_doneEvent_isNoop() {
        startConfirmingWithUnknownResult()
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT, "KRW")
        val eventId: Long = insertEvent(paymentKey, "DONE")
        processor.process(eventId)
        fakePgClient.reset()

        processor.process(eventId)

        assertThat(fakePgClient.lookupCalls()).isZero()
        assertEventDone(eventId, "CONFIRMED")
    }

    @Test
    @DisplayName("같은 주문의 웹훅 2건(다른 transmission-id)을 동시에 처리해도 충전은 1회다")
    fun process_twoEventsSameOrderConcurrently_topUpOnce() {
        startConfirmingWithUnknownResult()
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT, "KRW")
        fakePgClient.delay(Duration.ofMillis(300))
        val first: Long = insertEvent(paymentKey, "DONE")
        val second: Long = insertEvent(paymentKey, "DONE")

        runConcurrently(
            listOf(
                Runnable { processor.process(first) },
                Runnable { processor.process(second) },
            ),
        )

        assertThat(topUpCount()).isEqualTo(1)
        assertThat(balance()).isEqualTo(AMOUNT)
        assertThat(ledgerSum()).isEqualTo(balance())
        assertThat(orderStatus()).isEqualTo("PAID")
        assertThat(listOf(eventResult(first), eventResult(second)))
            .containsExactlyInAnyOrder("CONFIRMED", "ALREADY_PAID")
    }

    @Test
    @DisplayName("같은 웹훅 행을 두 처리기가 동시에 처리해도 충전은 1회다")
    fun process_sameEventConcurrently_topUpOnce() {
        startConfirmingWithUnknownResult()
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT, "KRW")
        fakePgClient.delay(Duration.ofMillis(300))
        val eventId: Long = insertEvent(paymentKey, "DONE")

        runConcurrently(
            listOf(
                Runnable { processor.process(eventId) },
                Runnable { processor.process(eventId) },
            ),
        )

        assertThat(topUpCount()).isEqualTo(1)
        assertThat(balance()).isEqualTo(AMOUNT)
        assertThat(orderStatus()).isEqualTo("PAID")
        // 두 처리 중 하나는 CONFIRMED, 다른 하나는 ALREADY_PAID이고 조건부 UPDATE로 먼저 끝난 쪽만 남는다.
        assertThat(eventResult(eventId)).isIn("CONFIRMED", "ALREADY_PAID")
    }

    @Test
    @DisplayName("PG 조회 중 다른 경로가 먼저 PAID로 확정하면 이 처리는 CONFIRMED가 아니라 ALREADY_PAID로 기록한다")
    fun process_paidByOtherPathDuringLookup_recordsAlreadyPaid() {
        val clientKey: String = startConfirmingWithUnknownResult()
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT, "KRW")
        fakePgClient.afterLookupDelay({ confirmTransactionService.completeApproved(orderId, paymentKey, clientKey) })
        val eventId: Long = insertEvent(paymentKey, "DONE")

        processor.process(eventId)

        assertThat(orderStatus()).isEqualTo("PAID")
        assertThat(topUpCount()).isEqualTo(1)
        assertThat(balance()).isEqualTo(AMOUNT)
        assertEventDone(eventId, "ALREADY_PAID")
    }

    @Test
    @DisplayName("승인 요청과 웹훅 처리가 동시에 DB 2에 들어와도 충전은 1회이고 승인 응답은 PAID다")
    fun confirmAndWebhookConcurrently_topUpOnce() {
        val clientKey: String = "client-" + UUID.randomUUID()
        fakePgClient.returnsApproved(paymentKey)
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT, "KRW")
        fakePgClient.delay(Duration.ofMillis(300))
        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            val confirm: Future<ChargeOrderConfirmResponse> =
                executor.submit(
                    Callable {
                        chargeOrderConfirmService.confirm(memberId, paymentKey, orderId, AMOUNT, clientKey)
                    },
                )
            awaitOrderStatus("CONFIRMING")
            val eventId: Long = insertEvent(paymentKey, "DONE")
            val webhook: Future<*> = executor.submit(Runnable { processor.process(eventId) })

            val response: ChargeOrderConfirmResponse = confirm.get(10, TimeUnit.SECONDS)
            webhook.get(10, TimeUnit.SECONDS)

            assertThat(response.status).isEqualTo(ChargeOrderStatus.PAID)
            assertThat(eventResult(eventId)).isIn("CONFIRMED", "ALREADY_PAID")
        } finally {
            executor.shutdownNow()
        }

        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertThat(topUpCount()).isEqualTo(1)
        assertThat(balance()).isEqualTo(AMOUNT)
        assertThat(ledgerSum()).isEqualTo(balance())
        assertThat(orderStatus()).isEqualTo("PAID")
    }

    @Test
    @DisplayName("웹훅이 먼저 확정한 뒤 늦게 도착한 승인 API의 DB 2는 같은 결과를 돌려주고 다시 충전하지 않는다")
    fun webhookFirstThenConfirmCompletes_topUpOnce() {
        val clientKey: String = startConfirmingWithUnknownResult()
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT, "KRW")
        processor.process(insertEvent(paymentKey, "DONE"))

        fakePgClient.reset()
        val again: ChargeOrderConfirmResponse = chargeOrderConfirmService.confirm(memberId, paymentKey, orderId, AMOUNT, clientKey)

        assertThat(again.status).isEqualTo(ChargeOrderStatus.PAID)
        assertThat(fakePgClient.confirmCalls()).isZero()
        assertThat(topUpCount()).isEqualTo(1)
        assertThat(balance()).isEqualTo(AMOUNT)
    }

    @Test
    @DisplayName("처리 대상 조회는 RECEIVED 행만 id 순서로 배치 크기만큼 돌려준다")
    fun findReceivedIds_returnsOnlyReceivedInIdOrder() {
        startConfirmingWithUnknownResult()
        fakePgClient.lookupReturnsUnknown()
        val done: Long = insertEvent(paymentKey, "DONE")
        processor.process(done)
        val first: Long = insertEvent(paymentKey, "DONE")
        val second: Long = insertEvent(paymentKey, "DONE")
        val third: Long = insertEvent(paymentKey, "DONE")

        assertThat(eventStore.findReceivedIds(2)).containsExactly(first, second)
        assertThat(eventStore.findReceivedIds(50)).containsExactly(first, second, third)
    }

    /** 승인 요청을 결과 모름으로 끝내 CONFIRMING + paymentKey + 클라이언트 멱등키를 남긴다. */
    private fun startConfirmingWithUnknownResult(): String {
        val clientKey: String = "client-" + UUID.randomUUID()
        fakePgClient.returnsUnknown("COMMON_ERROR", PgConfirmationResult.UnknownReason.INDETERMINATE)
        chargeOrderConfirmService.confirm(memberId, paymentKey, orderId, AMOUNT, clientKey)
        assertThat(orderStatus()).isEqualTo("CONFIRMING")
        fakePgClient.reset()
        return clientKey
    }

    private fun insertEvent(
        bodyPaymentKey: String,
        reportedStatus: String,
    ): Long {
        val transmissionId: String = "tx-" + UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO pg_webhook_event (transmission_id, event_type, order_id, payment_key, " +
                "reported_status, status, received_at) VALUES (?, ?, ?, ?, ?, 'RECEIVED', ?)",
            transmissionId,
            "PAYMENT_STATUS_CHANGED",
            orderId,
            bodyPaymentKey,
            reportedStatus,
            LocalDateTime.now(),
        )
        return jdbcTemplate.queryForObject(
            "SELECT id FROM pg_webhook_event WHERE transmission_id = ?",
            Long::class.javaObjectType,
            transmissionId,
        )
    }

    private fun runConcurrently(tasks: List<Runnable>) {
        val executor: ExecutorService = Executors.newFixedThreadPool(tasks.size)
        val ready: CountDownLatch = CountDownLatch(tasks.size)
        val start: CountDownLatch = CountDownLatch(1)
        try {
            val futures: MutableList<Future<*>> = ArrayList()
            for (task in tasks) {
                val callable: Callable<Void?> =
                    Callable {
                        ready.countDown()
                        start.await()
                        task.run()
                        null
                    }
                futures.add(executor.submit(callable))
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            for (future in futures) {
                future.get(10, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitOrderStatus(expected: String) {
        val deadline: Long = System.currentTimeMillis() + 5_000
        while (!expected.equals(orderStatus())) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("order did not reach " + expected)
            }
            Thread.sleep(10)
        }
    }

    private fun assertEventDone(
        eventId: Long,
        expectedResult: String,
    ) {
        val row: Map<String, Any?> =
            jdbcTemplate.queryForMap(
                "SELECT status, result, processed_at FROM pg_webhook_event WHERE id = ?",
                eventId,
            )
        assertThat(row.get("status")).isEqualTo("DONE")
        assertThat(row.get("result")).isEqualTo(expectedResult)
        assertThat(row.get("processed_at")).isNotNull()
    }

    private fun eventResult(eventId: Long): String? =
        jdbcTemplate.queryForObject(
            "SELECT result FROM pg_webhook_event WHERE id = ?",
            String::class.java,
            eventId,
        )

    private fun orderStatus(): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM charge_order WHERE order_id = ?",
            String::class.java,
            orderId,
        )

    private fun topUpCount(): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM credit_transaction WHERE member_id = ? AND type = 'TOP_UP'",
            Int::class.javaObjectType,
            memberId,
        )

    private fun balance(): Long =
        memberRepository
            .findById(memberId)
            .orElseThrow()
            .balance
            .toLong()

    private fun ledgerSum(): Long =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM credit_transaction WHERE member_id = ?",
            Long::class.javaObjectType,
            memberId,
        )

    private fun idempotencyCount(): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM idempotency_key WHERE member_id = ?",
            Int::class.javaObjectType,
            memberId,
        )

    companion object {
        private const val AMOUNT = 1_000L
    }
}
