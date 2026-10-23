package com.ovengers.slotkey.credit.pg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class TossPaymentsPgClient implements PgClient {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentsPgClient.class);
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String authorization;
    private final boolean configured;

    public TossPaymentsPgClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            String secretKey
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.configured = isConfigured(secretKey);
        this.authorization = configured ? basicAuthorization(secretKey) : null;
        if (!configured) {
            log.warn("TOSS_PAYMENTS_SECRET_KEY가 설정되지 않아 PG 승인·조회를 차단합니다.");
        }
    }

    @Override
    public PgConfirmationResult confirm(PgConfirmRequest request) {
        if (!configured) {
            PgConfirmationResult result = PgConfirmationResult.unknown(
                    "SECRET_KEY_NOT_CONFIGURED",
                    PgConfirmationResult.UnknownReason.MERCHANT_ERROR
            );
            warnConfirmationFailure(
                    request.orderId(),
                    result,
                    "IllegalStateException"
            );
            return result;
        }
        try {
            PaymentResponse response = restClient.post()
                    .uri("/v1/payments/confirm")
                    .header("Authorization", authorization)
                    .header(IDEMPOTENCY_KEY, request.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ConfirmBody(
                            request.paymentKey(),
                            request.orderId(),
                            request.amount()
                    ))
                    .retrieve()
                    .body(PaymentResponse.class);

            if (response != null
                    && "DONE".equals(response.status())
                    && matchesRequest(response, request)) {
                return PgConfirmationResult.approved(
                        response.paymentKey(),
                        response.method(),
                        response.easyPay() == null ? null : response.easyPay().provider()
                );
            }
            PgConfirmationResult result = indeterminate();
            String errorCode = response != null && "DONE".equals(response.status())
                    ? "RESPONSE_MISMATCH"
                    : "UNEXPECTED_SUCCESS_RESPONSE";
            warnConfirmationFailure(request.orderId(), result, errorCode, "N/A");
            return result;
        } catch (RestClientResponseException exception) {
            ErrorResponse error = readError(
                    request.orderId(),
                    exception.getResponseBodyAsByteArray()
            );
            String errorCode = error == null ? null : error.code();
            PgConfirmationResult result = errorCode == null
                    ? indeterminate()
                    : PgErrorClassifier.classify(exception.getStatusCode().value(), errorCode);
            warnConfirmationFailure(
                    request.orderId(),
                    result,
                    exception.getClass().getSimpleName()
            );
            return result;
        } catch (RestClientException exception) {
            PgConfirmationResult result = indeterminate();
            warnConfirmationFailure(
                    request.orderId(),
                    result,
                    exception.getClass().getSimpleName()
            );
            return result;
        }
    }

    @Override
    public PgPaymentLookupResult findByPaymentKey(String paymentKey) {
        if (!configured) {
            log.warn("PG 조회를 차단했습니다. reason=SECRET_KEY_NOT_CONFIGURED");
            return PgPaymentLookupResult.unknown();
        }
        try {
            PaymentResponse response = restClient.get()
                    .uri("/v1/payments/{paymentKey}", paymentKey)
                    .header("Authorization", authorization)
                    .retrieve()
                    .body(PaymentResponse.class);

            if (response == null) {
                return PgPaymentLookupResult.unknown();
            }
            return mapLookupStatus(response);
        } catch (RestClientException exception) {
            log.warn(
                    "PG 조회에 실패했습니다. lookupStatus=UNKNOWN exceptionClass={}",
                    exception.getClass().getSimpleName()
            );
            return PgPaymentLookupResult.unknown();
        }
    }

    private PgPaymentLookupResult mapLookupStatus(PaymentResponse response) {
        if (response.status() == null || response.status().isBlank()) {
            warnManualReview("MISSING");
            return unknownLookup(response);
        }
        return switch (response.status()) {
            case "DONE" -> approvedLookup(response);
            case "ABORTED", "EXPIRED" -> failedLookup(response);
            case "READY", "IN_PROGRESS" -> unknownLookup(response);
            case "CANCELED" -> {
                warnManualReview(response.status());
                yield failedLookup(response);
            }
            case "PARTIAL_CANCELED", "WAITING_FOR_DEPOSIT" -> {
                warnManualReview(response.status());
                yield unknownLookup(response);
            }
            default -> {
                warnManualReview(response.status());
                yield unknownLookup(response);
            }
        };
    }

    private PgPaymentLookupResult approvedLookup(PaymentResponse response) {
        return PgPaymentLookupResult.approved(
                response.paymentKey(),
                response.orderId(),
                response.totalAmount(),
                response.currency(),
                response.status()
        );
    }

    private PgPaymentLookupResult failedLookup(PaymentResponse response) {
        return PgPaymentLookupResult.failed(
                response.paymentKey(),
                response.orderId(),
                response.totalAmount(),
                response.currency(),
                response.status()
        );
    }

    private PgPaymentLookupResult unknownLookup(PaymentResponse response) {
        return PgPaymentLookupResult.unknown(
                response.paymentKey(),
                response.orderId(),
                response.totalAmount(),
                response.currency(),
                response.status()
        );
    }

    private void warnManualReview(String status) {
        log.warn("PG 조회 상태를 확인해야 합니다. status={}", status);
    }

    private ErrorResponse readError(String orderId, byte[] responseBody) {
        try {
            return objectMapper.readValue(responseBody, ErrorResponse.class);
        } catch (Exception exception) {
            log.warn(
                    "PG 오류 응답 파싱에 실패했습니다. orderId={} "
                            + "classification=UNKNOWN exceptionClass={}",
                    orderId,
                    exception.getClass().getSimpleName()
            );
            return null;
        }
    }

    private boolean matchesRequest(PaymentResponse response, PgConfirmRequest request) {
        return request.orderId().equals(response.orderId())
                && response.totalAmount() != null
                && request.amount() == response.totalAmount();
    }

    private void warnConfirmationFailure(
            String orderId,
            PgConfirmationResult result,
            String exceptionClass
    ) {
        warnConfirmationFailure(
                orderId,
                result,
                result.errorCode(),
                exceptionClass
        );
    }

    private void warnConfirmationFailure(
            String orderId,
            PgConfirmationResult result,
            String errorCode,
            String exceptionClass
    ) {
        log.warn(
                "PG 승인 결과를 확정하지 못했습니다. orderId={} classification={} "
                        + "unknownReason={} errorCode={} exceptionClass={}",
                orderId,
                result.type(),
                result.unknownReason(),
                errorCode == null ? "N/A" : errorCode,
                exceptionClass
        );
    }

    private static PgConfirmationResult indeterminate() {
        return PgConfirmationResult.unknown(
                null,
                PgConfirmationResult.UnknownReason.INDETERMINATE
        );
    }

    private static String basicAuthorization(String secretKey) {
        String credentials = secretKey + ":";
        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static boolean isConfigured(String secretKey) {
        return secretKey != null
                && !secretKey.isBlank()
                && !(secretKey.startsWith("${") && secretKey.endsWith("}"));
    }

    private record ConfirmBody(String paymentKey, String orderId, long amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PaymentResponse(
            String paymentKey,
            String orderId,
            String status,
            Long totalAmount,
            String currency,
            String method,
            EasyPay easyPay
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EasyPay(String provider) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorResponse(String code, String message) {
    }
}
