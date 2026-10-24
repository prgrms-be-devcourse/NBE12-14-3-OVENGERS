package com.ovengers.slotkey.credit.pg

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.nio.charset.StandardCharsets
import java.util.Base64

class TossPaymentsPgClient(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    secretKey: String,
) : PgClient {
    private val configured = isConfigured(secretKey)
    private val authorization = if (configured) basicAuthorization(secretKey) else null

    init {
        if (!configured) {
            log.warn("TOSS_PAYMENTS_SECRET_KEY가 설정되지 않아 PG 승인·조회를 차단합니다.")
        }
    }

    override fun confirm(request: PgConfirmRequest): PgConfirmationResult {
        if (!configured) {
            return PgConfirmationResult
                .unknown(
                    "SECRET_KEY_NOT_CONFIGURED",
                    PgConfirmationResult.UnknownReason.MERCHANT_ERROR,
                ).also {
                    warnConfirmationFailure(
                        request.orderId,
                        it,
                        "IllegalStateException",
                    )
                }
        }
        return try {
            val response =
                restClient
                    .post()
                    .uri("/v1/payments/confirm")
                    .header("Authorization", authorization)
                    .header(IDEMPOTENCY_KEY, request.idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        ConfirmBody(
                            paymentKey = request.paymentKey,
                            orderId = request.orderId,
                            amount = request.amount,
                        ),
                    ).retrieve()
                    .body(PaymentResponse::class.java)

            if (response != null && response.status == "DONE" && matchesRequest(response, request)) {
                PgConfirmationResult.approved(
                    response.paymentKey,
                    response.method,
                    response.easyPay?.provider,
                )
            } else {
                val result = indeterminate()
                val errorCode = if (response?.status == "DONE") "RESPONSE_MISMATCH" else "UNEXPECTED_SUCCESS_RESPONSE"
                warnConfirmationFailure(request.orderId, result, errorCode, "N/A")
                result
            }
        } catch (exception: RestClientResponseException) {
            val error = readError(request.orderId, exception.responseBodyAsByteArray)
            val errorCode = error?.code
            val result =
                if (errorCode == null) {
                    indeterminate()
                } else {
                    PgErrorClassifier.classify(exception.statusCode.value(), errorCode)
                }
            warnConfirmationFailure(request.orderId, result, exception.javaClass.simpleName)
            result
        } catch (exception: RestClientException) {
            val result = indeterminate()
            warnConfirmationFailure(request.orderId, result, exception.javaClass.simpleName)
            result
        }
    }

    override fun findByPaymentKey(paymentKey: String): PgPaymentLookupResult {
        if (!configured) {
            log.warn("PG 조회를 차단했습니다. reason=SECRET_KEY_NOT_CONFIGURED")
            return PgPaymentLookupResult.unknown()
        }
        return try {
            val response =
                restClient
                    .get()
                    .uri("/v1/payments/{paymentKey}", paymentKey)
                    .header("Authorization", authorization)
                    .retrieve()
                    .body(PaymentResponse::class.java)
                    ?: return PgPaymentLookupResult.unknown()
            mapLookupStatus(response)
        } catch (exception: RestClientException) {
            log.warn(
                "PG 조회에 실패했습니다. lookupStatus=UNKNOWN exceptionClass={}",
                exception.javaClass.simpleName,
            )
            PgPaymentLookupResult.unknown()
        }
    }

    private fun mapLookupStatus(response: PaymentResponse): PgPaymentLookupResult {
        val status = response.status
        if (status.isNullOrBlank()) {
            warnManualReview("MISSING")
            return unknownLookup(response)
        }
        return when (status) {
            "DONE" -> approvedLookup(response)
            "ABORTED", "EXPIRED" -> failedLookup(response)
            "READY", "IN_PROGRESS" -> unknownLookup(response)
            "CANCELED" -> {
                warnManualReview(status)
                failedLookup(response)
            }
            "PARTIAL_CANCELED", "WAITING_FOR_DEPOSIT" -> {
                warnManualReview(status)
                unknownLookup(response)
            }
            else -> {
                warnManualReview(status)
                unknownLookup(response)
            }
        }
    }

    private fun approvedLookup(response: PaymentResponse): PgPaymentLookupResult =
        PgPaymentLookupResult.approved(
            response.paymentKey,
            response.orderId,
            response.totalAmount,
            response.currency,
            response.status,
        )

    private fun failedLookup(response: PaymentResponse): PgPaymentLookupResult =
        PgPaymentLookupResult.failed(
            response.paymentKey,
            response.orderId,
            response.totalAmount,
            response.currency,
            response.status,
        )

    private fun unknownLookup(response: PaymentResponse): PgPaymentLookupResult =
        PgPaymentLookupResult.unknown(
            response.paymentKey,
            response.orderId,
            response.totalAmount,
            response.currency,
            response.status,
        )

    private fun warnManualReview(status: String) {
        log.warn("PG 조회 상태를 확인해야 합니다. status={}", status)
    }

    private fun readError(
        orderId: String,
        responseBody: ByteArray,
    ): ErrorResponse? =
        try {
            objectMapper.readValue(responseBody, ErrorResponse::class.java)
        } catch (exception: Exception) {
            log.warn(
                "PG 오류 응답 파싱에 실패했습니다. orderId={} classification=UNKNOWN exceptionClass={}",
                orderId,
                exception.javaClass.simpleName,
            )
            null
        }

    private fun matchesRequest(
        response: PaymentResponse,
        request: PgConfirmRequest,
    ): Boolean =
        request.orderId == response.orderId &&
            response.totalAmount != null &&
            request.amount == response.totalAmount

    private fun warnConfirmationFailure(
        orderId: String,
        result: PgConfirmationResult,
        exceptionClass: String,
    ) {
        warnConfirmationFailure(orderId, result, result.errorCode, exceptionClass)
    }

    private fun warnConfirmationFailure(
        orderId: String,
        result: PgConfirmationResult,
        errorCode: String?,
        exceptionClass: String,
    ) {
        log.warn(
            "PG 승인 결과를 확정하지 못했습니다. orderId={} classification={} " +
                "unknownReason={} errorCode={} exceptionClass={}",
            orderId,
            result.type,
            result.unknownReason,
            errorCode ?: "N/A",
            exceptionClass,
        )
    }

    private data class ConfirmBody(
        val paymentKey: String,
        val orderId: String,
        val amount: Long,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class PaymentResponse(
        val paymentKey: String?,
        val orderId: String?,
        val status: String?,
        val totalAmount: Long?,
        val currency: String?,
        val method: String?,
        val easyPay: EasyPay?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class EasyPay(
        val provider: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JvmRecord
    private data class ErrorResponse(
        val code: String?,
        val message: String?,
    )

    companion object {
        private val log = LoggerFactory.getLogger(TossPaymentsPgClient::class.java)
        private const val IDEMPOTENCY_KEY = "Idempotency-Key"

        private fun indeterminate(): PgConfirmationResult =
            PgConfirmationResult.unknown(
                null,
                PgConfirmationResult.UnknownReason.INDETERMINATE,
            )

        private fun basicAuthorization(secretKey: String): String {
            val credentials = "$secretKey:"
            return "Basic " +
                Base64.getEncoder().encodeToString(
                    credentials.toByteArray(StandardCharsets.UTF_8),
                )
        }

        private fun isConfigured(secretKey: String): Boolean =
            secretKey.isNotBlank() && !(secretKey.startsWith("\${") && secretKey.endsWith("}"))
    }
}
