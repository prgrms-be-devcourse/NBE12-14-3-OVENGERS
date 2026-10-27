package com.ovengers.slotkey.credit.pg

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Component
@Profile("test")
class FakePgClient : PgClient {
    private val confirmCalls = AtomicInteger()
    private val lookupCalls = AtomicInteger()
    private val transactionActiveDuringCalls = CopyOnWriteArrayList<Boolean>()

    @Volatile
    private var confirmationResult = PgConfirmationResult.approved("payment-default")

    @Volatile
    private var lookupResult = PgPaymentLookupResult.unknown()

    @Volatile
    private var delay = Duration.ZERO

    @Volatile
    private var lastConfirmRequest: PgConfirmRequest? = null

    @Volatile
    private var lastLookupPaymentKey: String? = null

    @Volatile
    private var lookupException: RuntimeException? = null

    @Volatile
    private var afterLookupDelay = Runnable {}

    override fun confirm(request: PgConfirmRequest): PgConfirmationResult {
        transactionActiveDuringCalls.add(TransactionSynchronizationManager.isActualTransactionActive())
        lastConfirmRequest = request
        confirmCalls.incrementAndGet()
        sleep()
        return confirmationResult
    }

    override fun findByPaymentKey(paymentKey: String): PgPaymentLookupResult {
        transactionActiveDuringCalls.add(TransactionSynchronizationManager.isActualTransactionActive())
        lastLookupPaymentKey = paymentKey
        lookupCalls.incrementAndGet()
        sleep()
        afterLookupDelay.run()
        lookupException?.let { throw it }
        return lookupResult
    }

    fun returnsApproved(paymentKey: String) {
        confirmationResult = PgConfirmationResult.approved(paymentKey)
    }

    fun returnsDeclined(errorCode: String) {
        confirmationResult = PgConfirmationResult.declined(errorCode)
    }

    fun returnsUnknown(
        errorCode: String?,
        reason: PgConfirmationResult.UnknownReason,
    ) {
        confirmationResult = PgConfirmationResult.unknown(errorCode, reason)
    }

    fun lookupReturnsApproved(paymentKey: String) {
        lookupResult = PgPaymentLookupResult.approved(paymentKey)
    }

    fun lookupReturnsApproved(
        paymentKey: String?,
        orderId: String?,
        totalAmount: Long,
        currency: String?,
    ) {
        lookupResult = PgPaymentLookupResult.approved(paymentKey, orderId, totalAmount, currency, "DONE")
    }

    fun lookupReturnsFailed(paymentKey: String) {
        lookupResult = PgPaymentLookupResult.failed(paymentKey)
    }

    fun lookupReturnsFailed(
        paymentKey: String?,
        orderId: String?,
        totalAmount: Long,
        currency: String?,
        rawStatus: String?,
    ) {
        lookupResult = PgPaymentLookupResult.failed(paymentKey, orderId, totalAmount, currency, rawStatus)
    }

    fun lookupReturnsUnknown() {
        lookupResult = PgPaymentLookupResult.unknown()
    }

    fun lookupReturnsUnknown(
        paymentKey: String?,
        orderId: String?,
        totalAmount: Long,
        currency: String?,
        rawStatus: String?,
    ) {
        lookupResult = PgPaymentLookupResult.unknown(paymentKey, orderId, totalAmount, currency, rawStatus)
    }

    fun lookupReturnsUnknownPaymentObject(
        paymentKey: String?,
        orderId: String?,
        totalAmount: Long?,
        currency: String?,
        rawStatus: String?,
    ) {
        lookupResult =
            PgPaymentLookupResult(PgPaymentLookupResult.Status.UNKNOWN, paymentKey, orderId, totalAmount, currency, rawStatus, true)
    }

    fun lookupThrows(exception: RuntimeException) {
        lookupException = exception
    }

    fun afterLookupDelay(action: Runnable) {
        afterLookupDelay = action
    }

    fun delay(delay: Duration) {
        this.delay = delay
    }

    fun confirmCalls(): Int = confirmCalls.get()

    fun lookupCalls(): Int = lookupCalls.get()

    fun lastConfirmRequest(): PgConfirmRequest? = lastConfirmRequest

    fun lastLookupPaymentKey(): String? = lastLookupPaymentKey

    fun transactionActiveDuringCalls(): List<Boolean> = java.util.List.copyOf(transactionActiveDuringCalls)

    fun reset() {
        confirmCalls.set(0)
        lookupCalls.set(0)
        transactionActiveDuringCalls.clear()
        confirmationResult = PgConfirmationResult.approved("payment-default")
        lookupResult = PgPaymentLookupResult.unknown()
        delay = Duration.ZERO
        lastConfirmRequest = null
        lastLookupPaymentKey = null
        lookupException = null
        afterLookupDelay = Runnable {}
    }

    private fun sleep() {
        try {
            Thread.sleep(delay.toMillis())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Fake PG delay interrupted", e)
        }
    }
}
