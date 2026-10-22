package com.ovengers.slotkey.credit.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class TossPaymentsPgClientTest {

    private static final String BASE_URL = "https://api.tosspayments.test";
    private static final String SECRET_KEY = "unit-test-secret";

    private MockRestServiceServer server;
    private TossPaymentsPgClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentsPgClient(builder.build(), new ObjectMapper(), SECRET_KEY);
    }

    @Test
    @DisplayName("승인 요청은 Basic 인증, 멱등키와 세 필드 JSON 본문을 정확히 전송한다")
    void confirm_sendsDocumentedHeadersAndBody() {
        String expectedAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                (SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8)
        );
        server.expect(once(), requestTo(BASE_URL + "/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, expectedAuthorization))
                .andExpect(header("Idempotency-Key", "pg-idempotency-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "paymentKey": "payment-key",
                          "orderId": "chg_order123",
                          "amount": 1000
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "payment-key",
                          "orderId": "chg_order123",
                          "status": "DONE",
                          "totalAmount": 1000
                        }
                        """, MediaType.APPLICATION_JSON));

        PgConfirmationResult result = client.confirm(new PgConfirmRequest(
                "payment-key",
                "chg_order123",
                1000L,
                "pg-idempotency-key"
        ));

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.APPROVED);
        assertThat(result.paymentKey()).isEqualTo("payment-key");
        server.verify();
    }

    @Test
    @DisplayName("승인 성공 응답의 새 필드는 무시한다")
    void confirm_ignoresUnknownResponseFields() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "payment-key",
                          "orderId": "chg_order123",
                          "status": "DONE",
                          "totalAmount": 1000,
                          "futureField": {"nested": true}
                        }
                        """, MediaType.APPLICATION_JSON));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.APPROVED);
        server.verify();
    }

    @Test
    @DisplayName("승인 응답의 결제수단(method)과 간편결제 제공사(easyPay.provider)를 결과에 담는다")
    void confirm_readsPaymentMethodAndEasyPayProvider() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "payment-key",
                          "orderId": "chg_order123",
                          "status": "DONE",
                          "totalAmount": 1000,
                          "method": "간편결제",
                          "easyPay": {"provider": "토스페이", "amount": 1000}
                        }
                        """, MediaType.APPLICATION_JSON));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.APPROVED);
        assertThat(result.paymentMethod()).isEqualTo("간편결제");
        assertThat(result.paymentProvider()).isEqualTo("토스페이");
        server.verify();
    }

    @Test
    @DisplayName("결제수단이 응답에 없으면 null로 두고 승인은 그대로 성공한다")
    void confirm_withoutMethod_stillApproved() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withSuccess("""
                        {"paymentKey": "payment-key", "orderId": "chg_order123", "status": "DONE", "totalAmount": 1000}
                        """, MediaType.APPLICATION_JSON));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.APPROVED);
        assertThat(result.paymentMethod()).isNull();
        assertThat(result.paymentProvider()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("4xx 에러 객체는 기존 오류 분류기의 명확한 거절로 연결한다")
    void confirm_userDecline_usesErrorClassifier(CapturedOutput output) {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"REJECT_CARD_PAYMENT","message":"declined"}
                                """));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.DECLINED);
        assertThat(result.errorCode()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(output)
                .contains("orderId=chg_order123")
                .contains("classification=DECLINED")
                .contains("errorCode=REJECT_CARD_PAYMENT")
                .contains("exceptionClass=BadRequest");
        server.verify();
    }

    @Test
    @DisplayName("ALREADY_PROCESSED_PAYMENT는 조회가 필요한 결과 모름으로 연결한다")
    void confirm_alreadyProcessed_requiresLookup() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"ALREADY_PROCESSED_PAYMENT","message":"processed"}
                                """));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.UNKNOWN);
        assertThat(result.unknownReason())
                .isEqualTo(PgConfirmationResult.UnknownReason.ALREADY_PROCESSED);
        server.verify();
    }

    @Test
    @DisplayName("5xx는 에러 코드 문구와 무관하게 결과 모름으로 분류한다")
    void confirm_serverError_isIndeterminate() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"REJECT_CARD_PAYMENT","message":"temporary"}
                                """));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.UNKNOWN);
        assertThat(result.unknownReason())
                .isEqualTo(PgConfirmationResult.UnknownReason.INDETERMINATE);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"message\":\"missing code\"}", "{\"code\":null}", "null"})
    @DisplayName("PG 에러 코드가 누락되면 어댑터가 결과 모름으로 처리한다")
    void confirm_missingErrorCode_isIndeterminate(String responseBody) {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseBody));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.UNKNOWN);
        assertThat(result.unknownReason()).isEqualTo(PgConfirmationResult.UnknownReason.INDETERMINATE);
        assertThat(result.errorCode()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("승인 200 응답이 DONE이 아니면 결과 모름으로 분류한다")
    void confirm_nonDoneResponse_isIndeterminate() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "payment-key",
                          "orderId": "chg_order123",
                          "status": "IN_PROGRESS",
                          "totalAmount": 1000
                        }
                        """, MediaType.APPLICATION_JSON));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.UNKNOWN);
        assertThat(result.unknownReason())
                .isEqualTo(PgConfirmationResult.UnknownReason.INDETERMINATE);
        server.verify();
    }

    @Test
    @DisplayName("승인 4xx 에러 본문이 JSON이 아니면 결과 모름으로 분류한다")
    void confirm_nonJsonErrorBody_isIndeterminate(CapturedOutput output) {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>proxy error</html>"));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.UNKNOWN);
        assertThat(result.unknownReason())
                .isEqualTo(PgConfirmationResult.UnknownReason.INDETERMINATE);
        assertThat(output)
                .contains("PG 오류 응답 파싱에 실패했습니다")
                .contains("orderId=chg_order123")
                .contains("classification=UNKNOWN")
                .contains("exceptionClass=");
        server.verify();
    }

    @Test
    @DisplayName("승인 200 응답이 깨진 JSON이면 결과 모름으로 분류한다")
    void confirm_malformedSuccessBody_isIndeterminate() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withSuccess("{broken", MediaType.APPLICATION_JSON));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.UNKNOWN);
        assertThat(result.unknownReason())
                .isEqualTo(PgConfirmationResult.UnknownReason.INDETERMINATE);
        server.verify();
    }

    @Test
    @DisplayName("조회 200 응답이 깨진 JSON이면 결과 모름으로 분류한다")
    void findByPaymentKey_malformedSuccessBody_isUnknown() {
        server.expect(requestTo(BASE_URL + "/v1/payments/payment-key"))
                .andRespond(withSuccess("{broken", MediaType.APPLICATION_JSON));

        PgPaymentLookupResult result = client.findByPaymentKey("payment-key");

        assertThat(result.status()).isEqualTo(PgPaymentLookupResult.Status.UNKNOWN);
        assertThat(result.paymentObjectReceived()).isFalse();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500})
    @DisplayName("조회 HTTP 실패는 Payment 객체가 없는 UNKNOWN으로 분류한다")
    void findByPaymentKey_httpFailure_hasNoPaymentObject(int status) {
        server.expect(requestTo(BASE_URL + "/v1/payments/payment-key"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        PgPaymentLookupResult result = client.findByPaymentKey("payment-key");

        assertThat(result.status()).isEqualTo(PgPaymentLookupResult.Status.UNKNOWN);
        assertThat(result.paymentObjectReceived()).isFalse();
        server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("networkFailures")
    @DisplayName("조회 네트워크 실패는 Payment 객체가 없는 UNKNOWN으로 분류한다")
    void findByPaymentKey_networkFailure_hasNoPaymentObject(
            String name,
            IOException failure
    ) {
        server.expect(requestTo(BASE_URL + "/v1/payments/payment-key"))
                .andRespond(withException(failure));

        PgPaymentLookupResult result = client.findByPaymentKey("payment-key");

        assertThat(result.status()).isEqualTo(PgPaymentLookupResult.Status.UNKNOWN);
        assertThat(result.paymentObjectReceived()).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("401 UNAUTHORIZED_KEY는 상점 설정 오류로 분류한다")
    void confirm_unauthorizedKey_isMerchantError() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"UNAUTHORIZED_KEY","message":"unauthorized"}
                                """));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.UNKNOWN);
        assertThat(result.unknownReason())
                .isEqualTo(PgConfirmationResult.UnknownReason.MERCHANT_ERROR);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "${TOSS_PAYMENTS_SECRET_KEY}"})
    @DisplayName("시크릿 키가 비었거나 미해석 placeholder이면 PG 호출 전에 차단한다")
    void missingSecretKey_blocksNetworkCall(String secretKey, CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer noCallServer = MockRestServiceServer.bindTo(builder).build();
        TossPaymentsPgClient unconfiguredClient = new TossPaymentsPgClient(
                builder.build(),
                new ObjectMapper(),
                secretKey
        );

        PgConfirmationResult confirmation = unconfiguredClient.confirm(request());
        PgPaymentLookupResult lookup = unconfiguredClient.findByPaymentKey("payment-key");

        assertThat(confirmation.unknownReason())
                .isEqualTo(PgConfirmationResult.UnknownReason.MERCHANT_ERROR);
        assertThat(lookup.status()).isEqualTo(PgPaymentLookupResult.Status.UNKNOWN);
        assertThat(output)
                .contains("TOSS_PAYMENTS_SECRET_KEY")
                .doesNotContain(secretKey.isEmpty() ? "never-match-empty-value" : secretKey);
        noCallServer.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("approvalResponseMismatches")
    @DisplayName("승인 응답의 주문 또는 금액이 요청과 다르면 결과 모름으로 둔다")
    void confirm_responseMismatch_isIndeterminate(
            String name,
            String responseOrderId,
            long responseAmount,
            CapturedOutput output
    ) {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "payment-key",
                          "orderId": "%s",
                          "status": "DONE",
                          "totalAmount": %d
                        }
                        """.formatted(responseOrderId, responseAmount), MediaType.APPLICATION_JSON));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.UNKNOWN);
        assertThat(result.unknownReason())
                .isEqualTo(PgConfirmationResult.UnknownReason.INDETERMINATE);
        assertThat(output)
                .contains("orderId=chg_order123")
                .contains("classification=UNKNOWN")
                .doesNotContain("payment-key");
        server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("networkFailures")
    @DisplayName("네트워크 예외는 승인 결과 모름으로 분류한다")
    void confirm_networkFailure_isIndeterminate(
            String name,
            IOException failure,
            CapturedOutput output
    ) {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withException(failure));

        PgConfirmationResult result = client.confirm(request());

        assertThat(result.type()).isEqualTo(PgConfirmationResult.Type.UNKNOWN);
        assertThat(result.unknownReason())
                .isEqualTo(PgConfirmationResult.UnknownReason.INDETERMINATE);
        assertThat(output)
                .contains("orderId=chg_order123")
                .contains("classification=UNKNOWN")
                .contains("errorCode=N/A")
                .contains("exceptionClass=ResourceAccessException")
                .doesNotContain(SECRET_KEY)
                .doesNotContain("payment-key");
        server.verify();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("lookupStatuses")
    @DisplayName("조회 Payment 상태를 보수적인 세 분류로 변환한다")
    void findByPaymentKey_mapsEveryPaymentStatus(
            String pgStatus,
            PgPaymentLookupResult.Status expectedStatus,
            boolean expectsWarning,
            CapturedOutput output
    ) {
        server.expect(requestTo(BASE_URL + "/v1/payments/payment-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "payment-key",
                          "orderId": "chg_order123",
                          "status": "%s",
                          "newField": true
                        }
                        """.formatted(pgStatus), MediaType.APPLICATION_JSON));

        PgPaymentLookupResult result = client.findByPaymentKey("payment-key");

        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.paymentKey()).isEqualTo("payment-key");
        assertThat(result.rawStatus()).isEqualTo(pgStatus);
        assertThat(output.getOut().contains("PG 조회 상태를 확인해야 합니다"))
                .isEqualTo(expectsWarning);
        server.verify();
    }

    @Test
    @DisplayName("조회 결과는 로컬 주문과 대조할 결제 식별 정보와 원시 상태를 보존한다")
    void findByPaymentKey_preservesPaymentFactsForIntegrityCheck() {
        server.expect(requestTo(BASE_URL + "/v1/payments/payment-key"))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "payment-key",
                          "orderId": "chg_order123",
                          "status": "DONE",
                          "totalAmount": 1000,
                          "currency": "KRW"
                        }
                        """, MediaType.APPLICATION_JSON));

        PgPaymentLookupResult result = client.findByPaymentKey("payment-key");

        assertThat(result.status()).isEqualTo(PgPaymentLookupResult.Status.APPROVED);
        assertThat(result.paymentObjectReceived()).isTrue();
        assertThat(result.paymentKey()).isEqualTo("payment-key");
        assertThat(result.orderId()).isEqualTo("chg_order123");
        assertThat(result.totalAmount()).isEqualTo(1_000L);
        assertThat(result.currency()).isEqualTo("KRW");
        assertThat(result.rawStatus()).isEqualTo("DONE");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"paymentKey\":\"pk\"}"})
    @DisplayName("조회 200 응답에 status가 없으면 경고하고 결과 모름으로 분류한다")
    void findByPaymentKey_missingStatus_isUnknown(String body, CapturedOutput output) {
        server.expect(requestTo(BASE_URL + "/v1/payments/payment-key"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        PgPaymentLookupResult result = client.findByPaymentKey("payment-key");

        assertThat(result.status()).isEqualTo(PgPaymentLookupResult.Status.UNKNOWN);
        assertThat(result.paymentObjectReceived()).isTrue();
        assertThat(output).contains("PG 조회 상태를 확인해야 합니다");
        server.verify();
    }

    @Test
    @DisplayName("로그에는 시크릿 키, Authorization 값과 paymentKey가 남지 않는다")
    void failures_doNotLogSensitiveValues(CapturedOutput output) {
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8)
        );
        server.expect(requestTo(BASE_URL + "/v1/payments/payment-key-sensitive"))
                .andRespond(withSuccess("""
                        {
                          "paymentKey": "payment-key-sensitive",
                          "orderId": "chg_order123",
                          "status": "PARTIAL_CANCELED"
                        }
                        """, MediaType.APPLICATION_JSON));

        client.findByPaymentKey("payment-key-sensitive");

        assertThat(output.getAll())
                .doesNotContain(SECRET_KEY)
                .doesNotContain(authorization)
                .doesNotContain("payment-key-sensitive");
        server.verify();
    }

    private PgConfirmRequest request() {
        return new PgConfirmRequest(
                "payment-key",
                "chg_order123",
                1000L,
                "pg-idempotency-key"
        );
    }

    private static Stream<Arguments> networkFailures() {
        return Stream.of(
                Arguments.of("연결 타임아웃", new ConnectException("connect timed out")),
                Arguments.of("읽기 타임아웃", new SocketTimeoutException("read timed out")),
                Arguments.of("소켓 종료", new SocketException("connection reset"))
        );
    }

    private static Stream<Arguments> lookupStatuses() {
        return Stream.of(
                Arguments.of("DONE", PgPaymentLookupResult.Status.APPROVED, false),
                Arguments.of("ABORTED", PgPaymentLookupResult.Status.FAILED, false),
                Arguments.of("EXPIRED", PgPaymentLookupResult.Status.FAILED, false),
                Arguments.of("READY", PgPaymentLookupResult.Status.UNKNOWN, false),
                Arguments.of("IN_PROGRESS", PgPaymentLookupResult.Status.UNKNOWN, false),
                Arguments.of("CANCELED", PgPaymentLookupResult.Status.FAILED, true),
                Arguments.of("PARTIAL_CANCELED", PgPaymentLookupResult.Status.UNKNOWN, true),
                Arguments.of("WAITING_FOR_DEPOSIT", PgPaymentLookupResult.Status.UNKNOWN, true)
        );
    }

    private static Stream<Arguments> approvalResponseMismatches() {
        return Stream.of(
                Arguments.of("주문 불일치", "chg_other", 1_000L),
                Arguments.of("금액 불일치", "chg_order123", 2_000L)
        );
    }
}
