package com.ovengers.slotkey.credit.webhook

import com.ovengers.slotkey.credit.dto.response.ChargeOrderResponse
import com.ovengers.slotkey.credit.pg.FakePgClient
import com.ovengers.slotkey.credit.pg.PgConfirmationResult
import com.ovengers.slotkey.credit.service.ChargeOrderConfirmService
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.ArrayList
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class TossWebhookIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var chargeOrderService: ChargeOrderService

    @Autowired
    private lateinit var chargeOrderConfirmService: ChargeOrderConfirmService

    @Autowired
    private lateinit var eventStore: PgWebhookEventStore

    @Autowired
    private lateinit var processor: PgWebhookEventProcessor

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var fakePgClient: FakePgClient

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate
    private var memberId: Long = 0L
    private lateinit var orderId: String
    private var initialBalance: Int = 0

    @BeforeEach
    fun setUp() {
        fakePgClient.reset()
        val member: Member =
            memberRepository.saveAndFlush(
                Member(
                    "webhook-" + UUID.randomUUID() + "@test.com",
                    "웹훅회원",
                ),
            )
        memberId = member.id!!
        initialBalance = member.balance
        val order: ChargeOrderResponse = chargeOrderService.create(memberId, AMOUNT)
        orderId = order.orderId
    }

    @AfterEach
    fun cleanUp() {
        jdbcTemplate.update("DELETE FROM pg_webhook_event")
        jdbcTemplate.update("DELETE FROM credit_transaction WHERE charge_order_id IS NOT NULL")
        jdbcTemplate.update("DELETE FROM charge_order")
        jdbcTemplate.update("DELETE FROM idempotency_key")
    }

    @Test
    @DisplayName("수신(200)과 처리를 거쳐 CONFIRMING 주문이 PG 조회 승인으로 PAID가 되고 중복 수신은 충전을 늘리지 않는다")
    fun receiveThenProcess_confirmsOnceThroughPgLookup() {
        val paymentKey: String = "pay-" + UUID.randomUUID()
        fakePgClient.returnsUnknown("COMMON_ERROR", PgConfirmationResult.UnknownReason.INDETERMINATE)
        chargeOrderConfirmService.confirm(memberId, paymentKey, orderId, AMOUNT, "client-" + UUID.randomUUID())
        fakePgClient.reset()
        fakePgClient.lookupReturnsApproved(paymentKey, orderId, AMOUNT, "KRW")
        val body: String = paymentStatusChanged(orderId, paymentKey, "DONE")

        mockMvc.perform(webhook(transmissionId(), body)).andExpect(status().isOk())
        mockMvc.perform(webhook(transmissionId(), body)).andExpect(status().isOk())
        assertThat(fakePgClient.lookupCalls()).isZero()
        for (eventId in eventStore.findReceivedIds(50)) {
            processor.process(eventId)
        }

        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM charge_order WHERE order_id = ?",
                String::class.java,
                orderId,
            ),
        ).isEqualTo("PAID")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_transaction WHERE member_id = ? AND type = 'TOP_UP'",
                Int::class.javaObjectType,
                memberId,
            ),
        ).isEqualTo(1)
        assertThat(memberRepository.findById(memberId).orElseThrow().balance)
            .isEqualTo(initialBalance + AMOUNT.toInt())
        assertThat(fakePgClient.lookupCalls()).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForList(
                "SELECT result FROM pg_webhook_event ORDER BY id",
                String::class.java,
            ),
        ).containsExactly("CONFIRMED", "ALREADY_PAID")
        assertThat(eventStore.findReceivedIds(50)).isEmpty()
    }

    @Test
    @DisplayName("인증 없이 받은 PAYMENT_STATUS_CHANGED를 RECEIVED로 기록하고 200을 반환한다")
    fun receive_recordsEventWithoutAuthentication() {
        val transmissionId: String = transmissionId()

        mockMvc
            .perform(
                webhook(transmissionId, paymentStatusChanged(orderId, "pay-hint", "DONE"))
                    .header("tosspayments-webhook-transmission-retried-count", "2"),
            ).andExpect(status().isOk())

        val row: Map<String, Any?> =
            jdbcTemplate.queryForMap(
                "SELECT * FROM pg_webhook_event WHERE transmission_id = ?",
                transmissionId,
            )
        assertThat(row.get("event_type")).isEqualTo("PAYMENT_STATUS_CHANGED")
        assertThat(row.get("order_id")).isEqualTo(orderId)
        assertThat(row.get("payment_key")).isEqualTo("pay-hint")
        assertThat(row.get("reported_status")).isEqualTo("DONE")
        assertThat((row.get("retried_count") as Number).toInt()).isEqualTo(2)
        assertThat(row.get("status")).isEqualTo("RECEIVED")
        assertThat(row.get("received_at")).isNotNull()
        assertThat(row.get("processed_at")).isNull()
    }

    @Test
    @DisplayName("본문의 DONE을 믿지 않는다: 수신만으로 주문·원장·잔액이 바뀌지 않고 PG도 호출하지 않는다")
    fun receive_doesNotTrustBodyOrCallPg() {
        mockMvc
            .perform(webhook(transmissionId(), paymentStatusChanged(orderId, "pay-hint", "DONE")))
            .andExpect(status().isOk())

        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM charge_order WHERE order_id = ?",
                String::class.java,
                orderId,
            ),
        ).isEqualTo("PENDING")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_transaction WHERE member_id = ?",
                Int::class.javaObjectType,
                memberId,
            ),
        ).isZero()
        assertThat(memberRepository.findById(memberId).orElseThrow().balance)
            .isEqualTo(initialBalance)
        assertThat(fakePgClient.lookupCalls()).isZero()
        assertThat(fakePgClient.confirmCalls()).isZero()
    }

    @Test
    @DisplayName("같은 transmission-id를 다시 받으면 200을 주고 기록은 한 행만 남는다")
    fun receive_duplicateTransmission_isIgnored() {
        val transmissionId: String = transmissionId()
        val body: String = paymentStatusChanged(orderId, "pay-hint", "DONE")

        mockMvc.perform(webhook(transmissionId, body)).andExpect(status().isOk())
        mockMvc
            .perform(
                webhook(transmissionId, body)
                    .header("tosspayments-webhook-transmission-retried-count", "1"),
            ).andExpect(status().isOk())

        assertThat(countEvents(transmissionId)).isEqualTo(1)
    }

    @Test
    @DisplayName("같은 transmission-id가 동시에 2건 도착해도 둘 다 200이고 기록은 한 행이다")
    fun receive_concurrentDuplicate_recordsOnce() {
        val transmissionId: String = transmissionId()
        val body: String = paymentStatusChanged(orderId, "pay-hint", "DONE")
        val threads: Int = 2
        val executor: ExecutorService = Executors.newFixedThreadPool(threads)
        val ready: CountDownLatch = CountDownLatch(threads)
        val start: CountDownLatch = CountDownLatch(1)
        try {
            val futures: MutableList<Future<Int>> = ArrayList()
            for (i in 0 until threads) {
                futures.add(
                    executor.submit(
                        Callable {
                            ready.countDown()
                            start.await()
                            mockMvc
                                .perform(webhook(transmissionId, body))
                                .andReturn()
                                .response
                                .status
                        },
                    ),
                )
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            for (future in futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(200)
            }
        } finally {
            executor.shutdownNow()
        }

        assertThat(countEvents(transmissionId)).isEqualTo(1)
    }

    @Test
    @DisplayName("이미 PAID인 주문의 웹훅도 200으로 기록만 하고 주문·원장을 바꾸지 않는다")
    fun receive_paidOrder_recordsOnlyWithoutSideEffects() {
        jdbcTemplate.update(
            "UPDATE charge_order SET status = 'PAID', payment_key = ? WHERE order_id = ?",
            "pay-paid",
            orderId,
        )
        val transmissionId: String = transmissionId()

        mockMvc
            .perform(webhook(transmissionId, paymentStatusChanged(orderId, "pay-paid", "DONE")))
            .andExpect(status().isOk())

        assertThat(countEvents(transmissionId)).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM charge_order WHERE order_id = ?",
                String::class.java,
                orderId,
            ),
        ).isEqualTo("PAID")
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_transaction WHERE member_id = ?",
                Int::class.javaObjectType,
                memberId,
            ),
        ).isZero()
        assertThat(fakePgClient.lookupCalls()).isZero()
    }

    @Test
    @DisplayName("PAYMENT_STATUS_CHANGED가 아닌 이벤트는 기록하지 않고 200을 반환한다")
    fun receive_otherEventType_isAcknowledgedWithoutRecord() {
        val transmissionId: String = transmissionId()
        val body: String = (
            """
            {"eventType":"BILLING_DELETED","createdAt":"2026-10-17T00:00:00.000000",
             "data":{"billingKey":"bk","reason":"삭제 API 요청"}}
            """.trimIndent() + "\n"
        )

        mockMvc.perform(webhook(transmissionId, body)).andExpect(status().isOk())

        assertThat(countEvents(transmissionId)).isZero()
    }

    @Test
    @DisplayName("없는 주문번호의 웹훅은 기록하지 않고 200을 반환한다")
    fun receive_unknownOrder_isAcknowledgedWithoutRecord() {
        val transmissionId: String = transmissionId()

        mockMvc
            .perform(
                webhook(
                    transmissionId,
                    paymentStatusChanged("chg_" + UUID.randomUUID(), "pay-x", "DONE"),
                ),
            ).andExpect(status().isOk())

        assertThat(countEvents(transmissionId)).isZero()
    }

    @Test
    @DisplayName("data.orderId가 없거나 너무 길면 기록하지 않고 200을 반환한다")
    fun receive_invalidOrderId_isAcknowledgedWithoutRecord() {
        val missing: String = transmissionId()
        val tooLong: String = transmissionId()

        mockMvc
            .perform(
                webhook(
                    missing,
                    (
                        """
                        {"eventType":"PAYMENT_STATUS_CHANGED","data":{"status":"DONE"}}
                        """.trimIndent() + "\n"
                    ),
                ),
            ).andExpect(status().isOk())
        mockMvc
            .perform(webhook(tooLong, paymentStatusChanged("x".repeat(65), "pay-x", "DONE")))
            .andExpect(status().isOk())

        assertThat(countEvents(missing)).isZero()
        assertThat(countEvents(tooLong)).isZero()
    }

    @Test
    @DisplayName("transmission-id 헤더가 없으면 400을 반환하고 기록하지 않는다")
    fun receive_missingTransmissionId_returns400() {
        mockMvc
            .perform(
                post(WEBHOOK_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(paymentStatusChanged(orderId, "pay-hint", "DONE")),
            ).andExpect(status().isBadRequest())

        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_webhook_event",
                Int::class.javaObjectType,
            ),
        ).isZero()
    }

    @Test
    @DisplayName("본문이 JSON이 아니면 400을 반환하고 기록하지 않는다")
    fun receive_malformedJson_returns400() {
        val transmissionId: String = transmissionId()

        mockMvc
            .perform(webhook(transmissionId, "{not-json"))
            .andExpect(status().isBadRequest())

        assertThat(countEvents(transmissionId)).isZero()
    }

    @Test
    @DisplayName("웹훅 경로라도 POST 외 메서드는 열지 않는다")
    fun webhookPath_otherMethod_stillRequiresAuthentication() {
        mockMvc.perform(get(WEBHOOK_PATH)).andExpect(status().isUnauthorized())
    }

    @Test
    @DisplayName("웹훅 경로를 연 뒤에도 충전 승인 API는 여전히 인증이 필요하다")
    fun otherChargePaths_stillRequireAuthentication() {
        mockMvc
            .perform(
                post("/api/v1/credits/charge-orders/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .content("{\"paymentKey\":\"p\",\"orderId\":\"" + orderId + "\",\"amount\":1000}"),
            ).andExpect(status().isUnauthorized())
        mockMvc
            .perform(
                post("/api/v1/webhooks/tosspayments/other")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isUnauthorized())
    }

    private fun webhook(
        transmissionId: String,
        body: String,
    ): MockHttpServletRequestBuilder =
        post(WEBHOOK_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .header("tosspayments-webhook-transmission-id", transmissionId)
            .header("tosspayments-webhook-transmission-time", "2026-10-17T12:19:21+09:00")
            .content(body)

    private fun paymentStatusChanged(
        orderId: String,
        paymentKey: String,
        status: String,
    ): String =
        (
            """
            {"eventType":"PAYMENT_STATUS_CHANGED","createdAt":"2026-10-17T00:00:00.000000",
             "data":{"mId":"tosspayments","version":"2022-11-16","paymentKey":"%s",
                     "orderId":"%s","status":"%s","totalAmount":1000,"unknownField":true}}
            """.trimIndent() + "\n"
        ).format(paymentKey, orderId, status)

    private fun transmissionId(): String = "whtrans_" + UUID.randomUUID()

    private fun countEvents(transmissionId: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_webhook_event WHERE transmission_id = ?",
            Int::class.javaObjectType,
            transmissionId,
        )

    companion object {
        private const val WEBHOOK_PATH = "/api/v1/webhooks/tosspayments"
        private const val AMOUNT = 1_000L
    }
}
