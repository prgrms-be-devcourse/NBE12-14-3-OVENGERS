package com.ovengers.slotkey.credit.service;

import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse;
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus;
import com.ovengers.slotkey.credit.pg.PgClient;
import com.ovengers.slotkey.credit.pg.PgConfirmationResult;
import com.ovengers.slotkey.credit.pg.PgPaymentLookupResult;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.global.idempotency.IdempotencyService;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ChargeOrderConfirmService {

    private static final Logger log = LoggerFactory.getLogger(ChargeOrderConfirmService.class);
    private static final String CLIENT_IDEMPOTENCY_CONSTRAINT =
            "uk_charge_order_member_client_idempotency";

    private final ChargeOrderConfirmTransactionService transactionService;
    private final PgClient pgClient;
    private final IdempotencyService idempotencyService;

    public ChargeOrderConfirmService(
            ChargeOrderConfirmTransactionService transactionService,
            PgClient pgClient,
            IdempotencyService idempotencyService
    ) {
        this.transactionService = transactionService;
        this.pgClient = pgClient;
        this.idempotencyService = idempotencyService;
    }

    public ChargeOrderConfirmResponse confirm(
            Long memberId,
            String paymentKey,
            String orderId,
            long amount,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        ChargeOrderPreparation preparation;
        try {
            preparation = transactionService.prepare(
                    memberId,
                    paymentKey,
                    orderId,
                    amount,
                    idempotencyKey
            );
        } catch (DataIntegrityViolationException exception) {
            if (isClientIdempotencyKeyConflict(exception)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            throw exception;
        }
        if (preparation.rejection() != null) {
            throw new BusinessException(preparation.rejection());
        }
        if (!preparation.shouldCallPg()) {
            if (preparation.currentResponse().status() == ChargeOrderStatus.PAID) {
                ChargeOrderConfirmResponse stored = idempotencyService.find(
                        idempotencyKey,
                        memberId,
                        ChargeOrderConfirmTransactionService.REQUEST_PATH,
                        ChargeOrderConfirmResponse.class
                );
                if (stored != null && orderId.equals(stored.orderId())) {
                    return stored;
                }
                return preparation.currentResponse();
            }
            return preparation.currentResponse();
        }

        PgConfirmationResult result = pgClient.confirm(preparation.pgRequest());
        if (result.type() == PgConfirmationResult.Type.APPROVED) {
            ChargeOrderConfirmResponse approved = completeApprovedOrKeepConfirming(
                    orderId,
                    paymentKey,
                    idempotencyKey
            );
            recordPaymentMethodQuietly(orderId, result);
            return approved;
        }
        if (result.type() == PgConfirmationResult.Type.DECLINED) {
            String errorCode = Objects.requireNonNull(
                    result.errorCode(),
                    "PG 거절 결과에는 오류 코드가 필요합니다."
            );
            transactionService.completeDeclined(orderId, errorCode);
            throw new BusinessException(ErrorCode.PAYMENT_DECLINED);
        }
        if (result.unknownReason() == PgConfirmationResult.UnknownReason.ALREADY_PROCESSED) {
            PgPaymentLookupResult lookup = pgClient.findByPaymentKey(paymentKey);
            if (lookup.status() == PgPaymentLookupResult.Status.APPROVED) {
                return completeApprovedOrKeepConfirming(
                        orderId,
                        paymentKey,
                        idempotencyKey
                );
            }
            log.warn(
                    "PG 승인 결과 확인 조회가 확정적이지 않습니다. "
                            + "orderId={} lookupStatus={}",
                    orderId,
                    lookup.status()
            );
        }
        if (result.unknownReason() == PgConfirmationResult.UnknownReason.MERCHANT_ERROR) {
            throw new BusinessException(ErrorCode.PG_CONFIRMATION_ERROR);
        }
        return transactionService.prepare(
                        memberId,
                        paymentKey,
                        orderId,
                        amount,
                        idempotencyKey
                )
                .currentResponse();
    }

    /** 표시용 결제수단 기록. 실패해도 이미 확정된 충전 결과를 바꾸지 않는다. */
    private void recordPaymentMethodQuietly(String orderId, PgConfirmationResult result) {
        if (result.paymentMethod() == null) {
            return;
        }
        try {
            transactionService.recordPaymentMethod(
                    orderId,
                    result.paymentMethod(),
                    result.paymentProvider());
        } catch (RuntimeException exception) {
            log.warn(
                    "결제수단 기록에 실패했습니다. orderId={} exceptionClass={}",
                    orderId,
                    exception.getClass().getSimpleName()
            );
        }
    }

    private ChargeOrderConfirmResponse completeApprovedOrKeepConfirming(
            String orderId,
            String paymentKey,
            String idempotencyKey
    ) {
        try {
            return transactionService.completeApproved(
                    orderId,
                    paymentKey,
                    idempotencyKey
            );
        } catch (RuntimeException exception) {
            String errorCode = exception instanceof BusinessException businessException
                    ? businessException.getErrorCode().getCode()
                    : "N/A";
            log.error(
                    "PG 승인 후 DB 2 반영에 실패했습니다. " +
                            "orderId={} exceptionClass={} errorCode={}",
                    orderId,
                    exception.getClass().getSimpleName(),
                    errorCode,
                    exception
            );
            ChargeOrderConfirmResponse current = transactionService.currentResponse(orderId);
            if (current.status() == ChargeOrderStatus.PAID
                    || current.status() == ChargeOrderStatus.CONFIRMING) {
                return current;
            }
            throw new BusinessException(ErrorCode.CHARGE_ORDER_STATE_CONFLICT);
        }
    }

    private boolean isClientIdempotencyKeyConflict(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                String constraintName = violation.getConstraintName();
                if (CLIENT_IDEMPOTENCY_CONSTRAINT.equals(constraintName)
                        || (constraintName != null
                            && constraintName.endsWith("." + CLIENT_IDEMPOTENCY_CONSTRAINT))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
