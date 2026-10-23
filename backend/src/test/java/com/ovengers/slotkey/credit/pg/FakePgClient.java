package com.ovengers.slotkey.credit.pg;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("test")
public class FakePgClient implements PgClient {

    private final AtomicInteger confirmCalls = new AtomicInteger();
    private final AtomicInteger lookupCalls = new AtomicInteger();
    private final List<Boolean> transactionActiveDuringCalls = new CopyOnWriteArrayList<>();

    private volatile PgConfirmationResult confirmationResult =
            PgConfirmationResult.approved("payment-default");
    private volatile PgPaymentLookupResult lookupResult =
            PgPaymentLookupResult.unknown();
    private volatile Duration delay = Duration.ZERO;
    private volatile PgConfirmRequest lastConfirmRequest;
    private volatile String lastLookupPaymentKey;
    private volatile RuntimeException lookupException;
    private volatile Runnable afterLookupDelay = () -> {
    };

    @Override
    public PgConfirmationResult confirm(PgConfirmRequest request) {
        transactionActiveDuringCalls.add(
                TransactionSynchronizationManager.isActualTransactionActive()
        );
        lastConfirmRequest = request;
        confirmCalls.incrementAndGet();
        sleep();
        return confirmationResult;
    }

    @Override
    public PgPaymentLookupResult findByPaymentKey(String paymentKey) {
        transactionActiveDuringCalls.add(
                TransactionSynchronizationManager.isActualTransactionActive()
        );
        lastLookupPaymentKey = paymentKey;
        lookupCalls.incrementAndGet();
        sleep();
        afterLookupDelay.run();
        if (lookupException != null) {
            throw lookupException;
        }
        return lookupResult;
    }

    public void returnsApproved(String paymentKey) {
        confirmationResult = PgConfirmationResult.approved(paymentKey);
    }

    public void returnsDeclined(String errorCode) {
        confirmationResult = PgConfirmationResult.declined(errorCode);
    }

    public void returnsUnknown(
            String errorCode,
            PgConfirmationResult.UnknownReason reason
    ) {
        confirmationResult = PgConfirmationResult.unknown(errorCode, reason);
    }

    public void lookupReturnsApproved(String paymentKey) {
        lookupResult = PgPaymentLookupResult.approved(paymentKey);
    }

    public void lookupReturnsApproved(
            String paymentKey,
            String orderId,
            long totalAmount,
            String currency
    ) {
        lookupResult = PgPaymentLookupResult.approved(
                paymentKey,
                orderId,
                totalAmount,
                currency,
                "DONE"
        );
    }

    public void lookupReturnsFailed(String paymentKey) {
        lookupResult = PgPaymentLookupResult.failed(paymentKey);
    }

    public void lookupReturnsFailed(
            String paymentKey,
            String orderId,
            long totalAmount,
            String currency,
            String rawStatus
    ) {
        lookupResult = PgPaymentLookupResult.failed(
                paymentKey,
                orderId,
                totalAmount,
                currency,
                rawStatus
        );
    }

    public void lookupReturnsUnknown() {
        lookupResult = PgPaymentLookupResult.unknown();
    }

    public void lookupReturnsUnknown(
            String paymentKey,
            String orderId,
            long totalAmount,
            String currency,
            String rawStatus
    ) {
        lookupResult = PgPaymentLookupResult.unknown(
                paymentKey,
                orderId,
                totalAmount,
                currency,
                rawStatus
        );
    }

    public void lookupReturnsUnknownPaymentObject(
            String paymentKey,
            String orderId,
            Long totalAmount,
            String currency,
            String rawStatus
    ) {
        lookupResult = new PgPaymentLookupResult(
                PgPaymentLookupResult.Status.UNKNOWN,
                paymentKey,
                orderId,
                totalAmount,
                currency,
                rawStatus,
                true
        );
    }

    public void lookupThrows(RuntimeException exception) {
        lookupException = exception;
    }

    public void afterLookupDelay(Runnable action) {
        afterLookupDelay = action;
    }

    public void delay(Duration delay) {
        this.delay = delay;
    }

    public int confirmCalls() {
        return confirmCalls.get();
    }

    public int lookupCalls() {
        return lookupCalls.get();
    }

    public PgConfirmRequest lastConfirmRequest() {
        return lastConfirmRequest;
    }

    public String lastLookupPaymentKey() {
        return lastLookupPaymentKey;
    }

    public List<Boolean> transactionActiveDuringCalls() {
        return List.copyOf(transactionActiveDuringCalls);
    }

    public void reset() {
        confirmCalls.set(0);
        lookupCalls.set(0);
        transactionActiveDuringCalls.clear();
        confirmationResult = PgConfirmationResult.approved("payment-default");
        lookupResult = PgPaymentLookupResult.unknown();
        delay = Duration.ZERO;
        lastConfirmRequest = null;
        lastLookupPaymentKey = null;
        lookupException = null;
        afterLookupDelay = () -> {
        };
    }

    private void sleep() {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fake PG delay interrupted", e);
        }
    }
}
