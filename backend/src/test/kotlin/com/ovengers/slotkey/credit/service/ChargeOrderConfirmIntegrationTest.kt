package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse
import com.ovengers.slotkey.credit.dto.response.ChargeOrderResponse
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.pg.FakePgClient
import com.ovengers.slotkey.credit.pg.PgConfirmationResult
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class ChargeOrderConfirmIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var chargeOrderService: ChargeOrderService

    @Autowired
    private lateinit var chargeOrderConfirmService: ChargeOrderConfirmService

    @Autowired
    private lateinit var creditGrantService: CreditGrantService

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var fakePgClient: FakePgClient
    private var memberId: Long = 0L
    private lateinit var orderId: String
    private lateinit var paymentKey: String

    @BeforeEach
    fun setUp() {
        fakePgClient.reset()
        memberId = createMember("owner")
        val order: ChargeOrderResponse = chargeOrderService.create(memberId, AMOUNT)
        orderId = order.orderId
        paymentKey = "payment-" + UUID.randomUUID()
    }

    @AfterEach
    fun cleanUpChargeData() {
        jdbcTemplate.update("DELETE FROM credit_transaction")
        jdbcTemplate.update("DELETE FROM charge_order")
        jdbcTemplate.update("DELETE FROM idempotency_key")
    }

    @Test
    @DisplayName("본인 주문이 아니면 거절하고 주문과 크레딧을 변경하지 않는다")
    fun confirm_notOwner_rejectsWithoutChanges() {
        val otherMemberId: Long = createMember("other")

        assertThatThrownBy({ confirm(otherMemberId, "owner-check") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER)

        assertThat(status()).isEqualTo("PENDING")
        assertThat(fakePgClient.confirmCalls()).isZero()
        assertBalanceInvariant(memberId)
        assertBalanceInvariant(otherMemberId)
    }

    @Test
    @DisplayName("본인 주문이 아니면서 금액도 다르면 감사 로그 없이 소유권 오류로 거절한다")
    fun confirm_notOwnerAndAmountMismatch_rejectsWithoutAudit() {
        val otherMemberId: Long = createMember("other-mismatch")

        assertThatThrownBy({
            chargeOrderConfirmService.confirm(
                otherMemberId,
                paymentKey,
                orderId,
                AMOUNT + 100,
                "not-owner-mismatch",
            )
        })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER)

        assertThat(status()).isEqualTo("PENDING")
        assertThat(countAudit("CHARGE_AMOUNT_MISMATCH")).isZero()
        assertThat(fakePgClient.confirmCalls()).isZero()
        assertBalanceInvariant(memberId)
        assertBalanceInvariant(otherMemberId)
    }

    @Test
    @DisplayName("금액 불일치는 PENDING을 유지하고 PG를 호출하지 않으며 위변조 의심 감사 로그를 남긴다")
    fun confirm_amountMismatch_auditsAndKeepsPending() {
        assertThatThrownBy({
            chargeOrderConfirmService.confirm(
                memberId,
                paymentKey,
                orderId,
                AMOUNT + 100,
                "amount-mismatch",
            )
        })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.CHARGE_ORDER_AMOUNT_MISMATCH,
            )

        assertThat(status()).isEqualTo("PENDING")
        assertThat(fakePgClient.confirmCalls()).isZero()
        assertThat(countAudit("CHARGE_AMOUNT_MISMATCH")).isEqualTo(1)
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("멱등키 재사용과 금액 불일치가 겹쳐도 위변조 감사 로그를 남긴다")
    fun confirm_reusedKeyAndAmountMismatch_alwaysAuditsTampering() {
        fakePgClient.returnsUnknown(
            "COMMON_ERROR",
            PgConfirmationResult.UnknownReason.INDETERMINATE,
        )
        confirm(memberId, "reused-with-mismatch")
        val anotherOrder: ChargeOrderResponse = chargeOrderService.create(memberId, AMOUNT)

        val exception =
            catchThrowableOfType(
                {
                    chargeOrderConfirmService.confirm(
                        memberId,
                        "another-payment-key",
                        anotherOrder.orderId,
                        AMOUNT + 100,
                        "reused-with-mismatch",
                    )
                },
                BusinessException::class.java,
            )

        assertThat(exception!!.errorCode.code)
            .isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(countAudit(anotherOrder.orderId, "CHARGE_AMOUNT_MISMATCH"))
            .isEqualTo(1)
        assertThat(status(anotherOrder.orderId)).isEqualTo("PENDING")
        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertBalanceInvariant(memberId)
    }

    @ParameterizedTest
    @EnumSource(value = MemberStatus::class, names = ["SUSPENDED", "WITHDRAWN"])
    @DisplayName("ACTIVE가 아닌 회원의 승인 요청은 PENDING을 유지하고 PG를 호출하지 않는다")
    fun confirm_inactiveMember_rejectsBeforePgCall(memberStatus: MemberStatus) {
        val member: Member = memberRepository.findById(memberId).orElseThrow()
        member.updateStatus(memberStatus)
        memberRepository.saveAndFlush(member)

        assertThatThrownBy({ confirm(memberId, "inactive-member") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_INACTIVE)

        assertThat(status()).isEqualTo("PENDING")
        assertThat(fakePgClient.confirmCalls()).isZero()
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("존재하지 않는 주문의 승인 요청은 404로 거절한다")
    fun confirm_orderNotFound_rejects() {
        assertThatThrownBy({
            chargeOrderConfirmService.confirm(
                memberId,
                paymentKey,
                "chg_00000000-0000-4000-8000-000000000000",
                AMOUNT,
                "missing-order",
            )
        })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHARGE_ORDER_NOT_FOUND)

        assertThat(fakePgClient.confirmCalls()).isZero()
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("PAID 주문에 같은 paymentKey로 재요청하면 다른 멱등키여도 현재 PAID를 반환한다")
    fun confirm_paidSamePaymentKey_returnsPaidWithoutPgCall() {
        fakePgClient.returnsApproved(paymentKey)
        val first: ChargeOrderConfirmResponse = confirm(memberId, "first-key")

        val refreshed: ChargeOrderConfirmResponse = confirm(memberId, "refresh-key")

        assertThat(first.status).isEqualTo(ChargeOrderStatus.PAID)
        assertThat(refreshed.status).isEqualTo(ChargeOrderStatus.PAID)
        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertTopUpOnce()
    }

    @Test
    @DisplayName("PAID 주문에 다른 paymentKey로 재요청하면 상태 충돌로 거절한다")
    fun confirm_paidDifferentPaymentKey_rejectsStateConflict() {
        fakePgClient.returnsApproved(paymentKey)
        confirm(memberId, "paid-first")

        assertThatThrownBy({
            chargeOrderConfirmService.confirm(
                memberId,
                "different-payment-key",
                orderId,
                AMOUNT,
                "paid-different-payment",
            )
        })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.CHARGE_ORDER_STATE_CONFLICT,
            )

        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertTopUpOnce()
    }

    @Test
    @DisplayName("CONFIRMING 주문 재요청은 PG를 다시 호출하지 않고 202 대상 상태를 반환한다")
    fun confirm_confirming_returnsCurrentStateWithoutPgCall() {
        fakePgClient.returnsUnknown(
            "COMMON_ERROR",
            PgConfirmationResult.UnknownReason.INDETERMINATE,
        )
        val first: ChargeOrderConfirmResponse = confirm(memberId, "unknown-first")

        val retried: ChargeOrderConfirmResponse = confirm(memberId, "another-key")

        assertThat(first.status).isEqualTo(ChargeOrderStatus.CONFIRMING)
        assertThat(retried.status).isEqualTo(ChargeOrderStatus.CONFIRMING)
        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("CONFIRMING 주문에 다른 paymentKey로 재요청해도 PG를 다시 호출하지 않는다")
    fun confirm_confirmingWithDifferentPaymentKey_returnsCurrentState() {
        fakePgClient.returnsUnknown(
            "COMMON_ERROR",
            PgConfirmationResult.UnknownReason.INDETERMINATE,
        )
        confirm(memberId, "confirming-first")

        val retried: ChargeOrderConfirmResponse =
            chargeOrderConfirmService.confirm(
                memberId,
                "different-payment-key",
                orderId,
                AMOUNT,
                "confirming-second",
            )

        assertThat(retried.status).isEqualTo(ChargeOrderStatus.CONFIRMING)
        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("FAILED 주문은 다시 승인하지 않고 상태 충돌로 거절한다")
    fun confirm_failedOrder_rejectsStateConflict() {
        fakePgClient.returnsDeclined("REJECT_CARD_PAYMENT")
        assertThatThrownBy({ confirm(memberId, "declined-first") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_DECLINED)

        assertThatThrownBy({ confirm(memberId, "declined-retry") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.CHARGE_ORDER_STATE_CONFLICT,
            )

        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertThat(status()).isEqualTo("FAILED")
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("동시에 같은 주문을 승인하면 두 스레드 중 하나만 PG를 호출하고 한 응답만 PAID가 된다")
    fun confirm_sameOrderConcurrently_callsPgOnce() {
        fakePgClient.returnsApproved(paymentKey)
        fakePgClient.delay(Duration.ofMillis(400))

        val ready: CountDownLatch = CountDownLatch(2)
        val start: CountDownLatch = CountDownLatch(1)
        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            val first: Callable<ChargeOrderConfirmResponse> =
                concurrentCall(
                    ready,
                    start,
                    "concurrent-1",
                )
            val second: Callable<ChargeOrderConfirmResponse> =
                concurrentCall(
                    ready,
                    start,
                    "concurrent-2",
                )
            val firstFuture: Future<ChargeOrderConfirmResponse> = executor.submit(first)
            val secondFuture: Future<ChargeOrderConfirmResponse> = executor.submit(second)

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            val responses: List<ChargeOrderConfirmResponse> =
                listOf(
                    firstFuture.get(5, TimeUnit.SECONDS),
                    secondFuture.get(5, TimeUnit.SECONDS),
                )

            assertThat(responses)
                .extracting<ChargeOrderStatus>({ it.status })
                .containsExactlyInAnyOrder(
                    ChargeOrderStatus.PAID,
                    ChargeOrderStatus.CONFIRMING,
                )
        } finally {
            start.countDown()
            executor.shutdownNow()
        }

        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertTopUpOnce()
    }

    @Test
    @DisplayName("같은 Idempotency-Key 성공 재요청은 현재 PAID를 반환하고 크레딧을 한 번만 증가시킨다")
    fun confirm_sameIdempotencyKey_returnsPaidAndCreditsOnce() {
        fakePgClient.returnsApproved(paymentKey)

        val first: ChargeOrderConfirmResponse = confirm(memberId, "same-key")
        val replayed: ChargeOrderConfirmResponse = confirm(memberId, "same-key")

        assertThat(listOf(first.status, replayed.status))
            .containsOnly(ChargeOrderStatus.PAID)
        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertTopUpOnce()
        assertThat(countIdempotencyRecords("same-key")).isEqualTo(1)
    }

    @Test
    @DisplayName("같은 Idempotency-Key를 다른 주문에 재사용하면 PG 호출 전에 거절한다")
    fun confirm_sameIdempotencyKeyForDifferentOrder_rejectsBeforePgCall() {
        fakePgClient.returnsApproved(paymentKey)
        confirm(memberId, "reused-key")

        val anotherOrder: ChargeOrderResponse = chargeOrderService.create(memberId, AMOUNT)
        val anotherPaymentKey: String = "payment-" + UUID.randomUUID()

        val exception =
            catchThrowableOfType(
                {
                    chargeOrderConfirmService.confirm(
                        memberId,
                        anotherPaymentKey,
                        anotherOrder.orderId,
                        AMOUNT,
                        "reused-key",
                    )
                },
                BusinessException::class.java,
            )

        assertThat(exception!!.errorCode.code)
            .isEqualTo("IDEMPOTENCY_KEY_REUSED")

        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertThat(status(orderId)).isEqualTo("PAID")
        assertThat(status(anotherOrder.orderId)).isEqualTo("PENDING")
        assertThat(topUpCount()).isEqualTo(1)
        assertThat(balance()).isEqualTo(AMOUNT)
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("FAILED 주문이 점유한 멱등키는 새 주문에서 재사용할 수 없다")
    fun confirm_failedOrderKeepsIdempotencyKey_andRejectsReuse() {
        fakePgClient.returnsDeclined("REJECT_CARD_PAYMENT")
        assertThatThrownBy({ confirm(memberId, "declined-key") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_DECLINED)
        val anotherOrder: ChargeOrderResponse = chargeOrderService.create(memberId, AMOUNT)

        val exception =
            catchThrowableOfType(
                {
                    chargeOrderConfirmService.confirm(
                        memberId,
                        "new-payment-key",
                        anotherOrder.orderId,
                        AMOUNT,
                        "declined-key",
                    )
                },
                BusinessException::class.java,
            )

        assertThat(exception!!.errorCode.code)
            .isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(clientIdempotencyKey(orderId)).isEqualTo("declined-key")
        assertThat(status(orderId)).isEqualTo("FAILED")
        assertThat(status(anotherOrder.orderId)).isEqualTo("PENDING")
        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("같은 멱등키로 서로 다른 주문을 동시에 승인하면 한 요청만 PG를 호출한다")
    fun confirm_sameIdempotencyKeyForDifferentOrdersConcurrently_callsPgOnce() {
        val anotherOrder: ChargeOrderResponse = chargeOrderService.create(memberId, AMOUNT)
        val anotherPaymentKey: String = "payment-" + UUID.randomUUID()
        fakePgClient.returnsApproved(paymentKey)
        fakePgClient.delay(Duration.ofMillis(400))
        val ready: CountDownLatch = CountDownLatch(2)
        val start: CountDownLatch = CountDownLatch(1)
        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            val first: Future<String> =
                executor.submit(
                    concurrentOutcome(
                        ready,
                        start,
                        orderId,
                        paymentKey,
                        "shared-concurrent-key",
                    ),
                )
            val second: Future<String> =
                executor.submit(
                    concurrentOutcome(
                        ready,
                        start,
                        anotherOrder.orderId,
                        anotherPaymentKey,
                        "shared-concurrent-key",
                    ),
                )

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            assertThat(
                listOf(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS),
                ),
            ).containsExactlyInAnyOrder("PAID", "IDEMPOTENCY_KEY_REUSED")
        } finally {
            start.countDown()
            executor.shutdownNow()
        }

        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertThat(listOf(status(orderId), status(anotherOrder.orderId)))
            .containsExactlyInAnyOrder("PAID", "PENDING")
        assertThat(topUpCount()).isEqualTo(1)
        assertThat(balance()).isEqualTo(AMOUNT)
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("서로 다른 주문을 동시에 승인하면 각각 PAID와 TOP_UP으로 반영한다")
    fun confirm_differentOrdersConcurrently_topUpsEachOrder() {
        val anotherOrder: ChargeOrderResponse = chargeOrderService.create(memberId, AMOUNT)
        val anotherPaymentKey: String = "payment-" + UUID.randomUUID()
        fakePgClient.returnsApproved(paymentKey)
        fakePgClient.delay(Duration.ofMillis(400))
        val ready: CountDownLatch = CountDownLatch(2)
        val start: CountDownLatch = CountDownLatch(1)
        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            val first: Future<String> =
                executor.submit(
                    concurrentOutcome(
                        ready,
                        start,
                        orderId,
                        paymentKey,
                        "different-order-key-1",
                    ),
                )
            val second: Future<String> =
                executor.submit(
                    concurrentOutcome(
                        ready,
                        start,
                        anotherOrder.orderId,
                        anotherPaymentKey,
                        "different-order-key-2",
                    ),
                )

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            assertThat(
                listOf(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS),
                ),
            ).containsOnly("PAID")
        } finally {
            start.countDown()
            executor.shutdownNow()
        }

        assertThat(fakePgClient.confirmCalls()).isEqualTo(2)
        assertThat(status(orderId)).isEqualTo("PAID")
        assertThat(status(anotherOrder.orderId)).isEqualTo("PAID")
        assertThat(topUpCountForOrder(orderId)).isEqualTo(1)
        assertThat(topUpCountForOrder(anotherOrder.orderId)).isEqualTo(1)
        assertThat(balance()).isEqualTo(AMOUNT * 2)
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("PG 사용자 거절은 FAILED로 바꾸고 크레딧을 증가시키지 않는다")
    fun confirm_pgDeclined_marksFailed() {
        fakePgClient.returnsDeclined("REJECT_CARD_PAYMENT")

        assertThatThrownBy({ confirm(memberId, "declined") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_DECLINED)

        assertThat(status()).isEqualTo("FAILED")
        assertThat(topUpCount()).isZero()
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("PG 결과 모름 뒤 같은 멱등키 재요청은 저장 응답을 재생하지 않고 현재 CONFIRMING을 반환한다")
    fun confirm_unknownSameKey_readsCurrentStateWithoutPgRetry() {
        fakePgClient.returnsUnknown(
            "COMMON_ERROR",
            PgConfirmationResult.UnknownReason.INDETERMINATE,
        )

        val first: ChargeOrderConfirmResponse = confirm(memberId, "unknown-key")
        val retried: ChargeOrderConfirmResponse = confirm(memberId, "unknown-key")

        assertThat(listOf(first.status, retried.status))
            .containsOnly(ChargeOrderStatus.CONFIRMING)
        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertThat(countIdempotencyRecords("unknown-key")).isZero()
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("ALREADY_PROCESSED_PAYMENT는 PG 조회가 승인 상태이면 DB 2 경로로 PAID를 반영한다")
    fun confirm_alreadyProcessed_lookupApproved_marksPaid() {
        fakePgClient.returnsUnknown(
            "ALREADY_PROCESSED_PAYMENT",
            PgConfirmationResult.UnknownReason.ALREADY_PROCESSED,
        )
        fakePgClient.lookupReturnsApproved(paymentKey)

        val response: ChargeOrderConfirmResponse = confirm(memberId, "already-processed")

        assertThat(response.status).isEqualTo(ChargeOrderStatus.PAID)
        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertThat(fakePgClient.lookupCalls()).isEqualTo(1)
        assertThat(fakePgClient.transactionActiveDuringCalls())
            .containsExactly(false, false)
        assertTopUpOnce()
    }

    @Test
    @DisplayName("ALREADY_PROCESSED_PAYMENT 조회가 실패 상태여도 2단계에서는 CONFIRMING을 유지한다")
    fun confirm_alreadyProcessed_lookupFailed_keepsConfirming() {
        fakePgClient.returnsUnknown(
            "ALREADY_PROCESSED_PAYMENT",
            PgConfirmationResult.UnknownReason.ALREADY_PROCESSED,
        )
        fakePgClient.lookupReturnsFailed(paymentKey)

        val response: ChargeOrderConfirmResponse = confirm(memberId, "lookup-failed")

        assertThat(response.status).isEqualTo(ChargeOrderStatus.CONFIRMING)
        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertThat(fakePgClient.lookupCalls()).isEqualTo(1)
        assertThat(topUpCount()).isZero()
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("ALREADY_PROCESSED_PAYMENT 조회 결과를 모르면 CONFIRMING을 유지한다")
    fun confirm_alreadyProcessed_lookupUnknown_keepsConfirming() {
        fakePgClient.returnsUnknown(
            "ALREADY_PROCESSED_PAYMENT",
            PgConfirmationResult.UnknownReason.ALREADY_PROCESSED,
        )
        fakePgClient.lookupReturnsUnknown()

        val response: ChargeOrderConfirmResponse = confirm(memberId, "lookup-unknown")

        assertThat(response.status).isEqualTo(ChargeOrderStatus.CONFIRMING)
        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertThat(fakePgClient.lookupCalls()).isEqualTo(1)
        assertThat(topUpCount()).isZero()
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("PG 인증 키 등 우리 측 문제는 CONFIRMING을 유지하고 500 오류로 반환한다")
    fun confirm_merchantError_keepsConfirming() {
        fakePgClient.returnsUnknown(
            "INVALID_API_KEY",
            PgConfirmationResult.UnknownReason.MERCHANT_ERROR,
        )

        assertThatThrownBy({ confirm(memberId, "merchant-error") })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.PG_CONFIRMATION_ERROR,
            )

        assertThat(status()).isEqualTo("CONFIRMING")
        assertThat(topUpCount()).isZero()
        assertBalanceInvariant(memberId)
    }

    @Test
    @DisplayName("PG 승인 호출 시점에는 실제 트랜잭션이 없고 주문용 PG 멱등키를 전달한다")
    fun confirm_pgCallRunsOutsideTransaction() {
        fakePgClient.returnsApproved(paymentKey)

        confirm(memberId, "transaction-check")

        val storedPgIdempotencyKey =
            jdbcTemplate.queryForObject(
                "SELECT pg_idempotency_key FROM charge_order WHERE order_id = ?",
                String::class.java,
                orderId,
            )
        assertThat(fakePgClient.transactionActiveDuringCalls())
            .containsExactly(false)
        assertThat(fakePgClient.lastConfirmRequest()!!.idempotencyKey)
            .isEqualTo(storedPgIdempotencyKey)
            .hasSizeLessThanOrEqualTo(300)
    }

    @Test
    @DisplayName("승인 성공은 PAID, TOP_UP 원장, 잔액 증가를 정확히 한 번 반영한다")
    fun confirm_approved_marksPaidAndTopUpsAtomically() {
        fakePgClient.returnsApproved(paymentKey)

        val response: ChargeOrderConfirmResponse = confirm(memberId, "approved")

        assertThat(response.status).isEqualTo(ChargeOrderStatus.PAID)
        assertTopUpOnce()
    }

    @Test
    @DisplayName("PG 승인 후 DB 2가 실패하면 CONFIRMING과 크레딧 불변을 유지한다")
    fun confirm_approvedButDb2Fails_keepsConfirming() {
        creditGrantService.grantAdminCredit(
            memberId,
            Int.MAX_VALUE,
            "DB 2 실패 경계 테스트",
        )
        fakePgClient.returnsApproved(paymentKey)

        val response: ChargeOrderConfirmResponse = confirm(memberId, "db2-failure")

        assertThat(response.status).isEqualTo(ChargeOrderStatus.CONFIRMING)
        assertThat(status()).isEqualTo("CONFIRMING")
        assertThat(fakePgClient.confirmCalls()).isEqualTo(1)
        assertThat(topUpCount()).isZero()
        assertThat(balance()).isEqualTo(Int.MAX_VALUE.toLong())
        assertBalanceInvariant(memberId)
    }

    private fun concurrentCall(
        ready: CountDownLatch,
        start: CountDownLatch,
        idempotencyKey: String,
    ): Callable<ChargeOrderConfirmResponse> =
        Callable {
            ready.countDown()
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw IllegalStateException("동시 시작 latch 시간 초과")
            }
            confirm(memberId, idempotencyKey)
        }

    private fun concurrentOutcome(
        ready: CountDownLatch,
        start: CountDownLatch,
        targetOrderId: String,
        targetPaymentKey: String,
        idempotencyKey: String,
    ): Callable<String> =
        Callable {
            ready.countDown()
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw IllegalStateException("동시 시작 latch 시간 초과")
            }
            try {
                chargeOrderConfirmService
                    .confirm(
                        memberId,
                        targetPaymentKey,
                        targetOrderId,
                        AMOUNT,
                        idempotencyKey,
                    ).status.name
            } catch (exception: BusinessException) {
                exception.errorCode.code
            }
        }

    private fun confirm(
        requesterId: Long,
        idempotencyKey: String,
    ): ChargeOrderConfirmResponse =
        chargeOrderConfirmService.confirm(
            requesterId,
            paymentKey,
            orderId,
            AMOUNT,
            idempotencyKey,
        )

    private fun createMember(label: String): Long {
        val member: Member =
            memberRepository.saveAndFlush(
                Member(
                    label + "-" + UUID.randomUUID() + "@test.com",
                    label,
                ),
            )
        return member.id!!
    }

    private fun status(): String? = status(orderId)

    private fun status(targetOrderId: String): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM charge_order WHERE order_id = ?",
            String::class.java,
            targetOrderId,
        )

    private fun topUpCount(): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM credit_transaction " +
                "WHERE member_id = ? AND type = 'TOP_UP'",
            Int::class.javaObjectType,
            memberId,
        )!!

    private fun countAudit(action: String): Int = countAudit(orderId, action)

    private fun countAudit(
        targetOrderId: String,
        action: String,
    ): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE target_type = 'CHARGE_ORDER' " +
                "AND target_id = (SELECT id FROM charge_order WHERE order_id = ?) " +
                "AND action = ?",
            Int::class.javaObjectType,
            targetOrderId,
            action,
        )!!

    private fun clientIdempotencyKey(targetOrderId: String): String? =
        jdbcTemplate.queryForObject(
            "SELECT client_idempotency_key FROM charge_order WHERE order_id = ?",
            String::class.java,
            targetOrderId,
        )

    private fun topUpCountForOrder(targetOrderId: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM credit_transaction ct " +
                "JOIN charge_order co ON co.id = ct.charge_order_id " +
                "WHERE co.order_id = ? AND ct.type = 'TOP_UP'",
            Int::class.javaObjectType,
            targetOrderId,
        )!!

    private fun countIdempotencyRecords(key: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM idempotency_key WHERE idempotency_key = ? " +
                "AND member_id = ? AND request_path = '/credits/charge-orders/confirm'",
            Int::class.javaObjectType,
            key,
            memberId,
        )!!

    private fun assertTopUpOnce() {
        assertThat(status()).isEqualTo("PAID")
        assertThat(topUpCount()).isEqualTo(1)
        assertThat(balance()).isEqualTo(AMOUNT)
        assertBalanceInvariant(memberId)
    }

    private fun balance(): Long =
        jdbcTemplate.queryForObject(
            "SELECT balance FROM member WHERE id = ?",
            Long::class.javaObjectType,
            memberId,
        )!!

    private fun assertBalanceInvariant(targetMemberId: Long) {
        val balance =
            jdbcTemplate.queryForObject(
                "SELECT balance FROM member WHERE id = ?",
                Long::class.javaObjectType,
                targetMemberId,
            )
        val ledgerSum =
            jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM credit_transaction WHERE member_id = ?",
                Long::class.javaObjectType,
                targetMemberId,
            )
        assertThat(ledgerSum).isEqualTo(balance)
    }

    companion object {
        private const val AMOUNT = 10_000L
    }
}
