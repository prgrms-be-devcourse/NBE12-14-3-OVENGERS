package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.pg.PgClient
import com.ovengers.slotkey.credit.pg.PgConfirmRequest
import com.ovengers.slotkey.credit.pg.PgConfirmationResult
import com.ovengers.slotkey.credit.pg.PgPaymentLookupResult
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.idempotency.IdempotencyService
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.nullValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException

@ExtendWith(MockitoExtension::class, OutputCaptureExtension::class)
class ChargeOrderConfirmServiceTest {
    @Mock
    private lateinit var transactionService: ChargeOrderConfirmTransactionService

    @Mock
    private lateinit var pgClient: PgClient

    @Mock
    private lateinit var idempotencyService: IdempotencyService

    @Test
    @DisplayName("저장된 멱등 응답의 주문이 현재 주문과 다르면 현재 PAID 응답을 반환한다")
    fun confirm_paidSameKey_doesNotReplayResponseForDifferentOrder() {
        val current: ChargeOrderConfirmResponse =
            ChargeOrderConfirmResponse(
                "chg_order",
                1_000L,
                ChargeOrderStatus.PAID,
            )
        val stored: ChargeOrderConfirmResponse =
            ChargeOrderConfirmResponse(
                "chg_another_order",
                900L,
                ChargeOrderStatus.PAID,
            )
        given(
            transactionService.prepare(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "same-key",
            ),
        ).willReturn(ChargeOrderPreparation.current(current))
        given(
            idempotencyService.find(
                "same-key",
                7L,
                ChargeOrderConfirmTransactionService.REQUEST_PATH,
                ChargeOrderConfirmResponse::class.java,
            ),
        ).willReturn(stored)

        val service: ChargeOrderConfirmService =
            ChargeOrderConfirmService(
                transactionService,
                pgClient,
                idempotencyService,
            )

        val response: ChargeOrderConfirmResponse =
            service.confirm(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "same-key",
            )

        assertThat(response).isEqualTo(current)
    }

    @Test
    @DisplayName("클라이언트 멱등키 제약이 아닌 무결성 오류는 상태 충돌로 바꾸지 않는다")
    fun confirm_unrelatedIntegrityViolation_propagatesOriginalException() {
        val failure: DataIntegrityViolationException = DataIntegrityViolationException("another_constraint")
        given(
            transactionService.prepare(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "same-key",
            ),
        ).willThrow(failure)
        val service: ChargeOrderConfirmService =
            ChargeOrderConfirmService(
                transactionService,
                pgClient,
                idempotencyService,
            )

        assertThatThrownBy({
            service.confirm(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "same-key",
            )
        }).isSameAs(failure)
    }

    @Test
    @DisplayName("MySQL 멱등키 UNIQUE 위반은 멱등키 재사용 오류로 변환한다")
    fun confirm_mysqlClientIdempotencyKeyViolation_returnsReusedError() {
        val sqlException: SQLException =
            SQLException(
                "Duplicate entry '7-key' for key " +
                    "'charge_order.uk_charge_order_member_client_idempotency'",
            )
        val constraintViolation: ConstraintViolationException =
            ConstraintViolationException(
                "could not execute statement",
                sqlException,
                "charge_order.uk_charge_order_member_client_idempotency",
            )
        val failure: DataIntegrityViolationException =
            DataIntegrityViolationException(
                "could not execute statement",
                constraintViolation,
            )
        given(
            transactionService.prepare(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "same-key",
            ),
        ).willThrow(failure)
        val service: ChargeOrderConfirmService =
            ChargeOrderConfirmService(
                transactionService,
                pgClient,
                idempotencyService,
            )

        assertThatThrownBy({
            service.confirm(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "same-key",
            )
        })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_REUSED)
    }

    @Test
    @DisplayName("예외 문구에 제약명이 없어도 구조화된 제약명으로 멱등키 충돌을 판별한다")
    fun confirm_structuredClientIdempotencyKeyViolation_returnsReusedError() {
        val constraintViolation: ConstraintViolationException =
            ConstraintViolationException(
                "could not execute statement",
                SQLException("Duplicate entry"),
                "uk_charge_order_member_client_idempotency",
            )
        val failure: DataIntegrityViolationException =
            DataIntegrityViolationException(
                "database constraint violation",
                constraintViolation,
            )
        given(
            transactionService.prepare(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "same-key",
            ),
        ).willThrow(failure)
        val service: ChargeOrderConfirmService =
            ChargeOrderConfirmService(
                transactionService,
                pgClient,
                idempotencyService,
            )

        assertThatThrownBy({
            service.confirm(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "same-key",
            )
        })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_KEY_REUSED)
    }

    @Test
    @DisplayName("PG 승인 뒤 DB 2 상태 충돌은 CONFIRMING을 반환하고 분류 가능한 로그를 남긴다")
    fun confirm_db2StateConflict_keepsConfirmingAndLogsClassification(output: CapturedOutput) {
        val pgRequest: PgConfirmRequest =
            PgConfirmRequest(
                "payment-key",
                "chg_order",
                1_000L,
                "pg-key",
            )
        val confirming: ChargeOrderConfirmResponse =
            ChargeOrderConfirmResponse(
                "chg_order",
                1_000L,
                ChargeOrderStatus.CONFIRMING,
            )
        given(
            transactionService.prepare(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "client-key",
            ),
        ).willReturn(ChargeOrderPreparation.callPg(pgRequest))
        given(pgClient.confirm(pgRequest))
            .willReturn(PgConfirmationResult.approved("payment-key"))
        given(
            transactionService.completeApproved(
                "chg_order",
                "payment-key",
                "client-key",
            ),
        ).willThrow(BusinessException(ErrorCode.CHARGE_ORDER_STATE_CONFLICT))
        given(transactionService.currentResponse("chg_order")).willReturn(confirming)
        val service: ChargeOrderConfirmService =
            ChargeOrderConfirmService(
                transactionService,
                pgClient,
                idempotencyService,
            )

        val response: ChargeOrderConfirmResponse =
            service.confirm(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "client-key",
            )

        assertThat(response).isEqualTo(confirming)
        assertThat(output)
            .contains("exceptionClass=BusinessException")
            .contains("errorCode=CHARGE_ORDER_STATE_CONFLICT")
    }

    private fun approvedFlow(
        approved: PgConfirmationResult,
        paid: ChargeOrderConfirmResponse,
    ): ChargeOrderConfirmService {
        val pgRequest: PgConfirmRequest = PgConfirmRequest("payment-key", "chg_order", 1_000L, "pg-key")
        given(transactionService.prepare(7L, "payment-key", "chg_order", 1_000L, "client-key"))
            .willReturn(ChargeOrderPreparation.callPg(pgRequest))
        given(pgClient.confirm(pgRequest)).willReturn(approved)
        given(transactionService.completeApproved("chg_order", "payment-key", "client-key")).willReturn(paid)
        return ChargeOrderConfirmService(transactionService, pgClient, idempotencyService)
    }

    @Test
    @DisplayName("승인 성공 뒤 PG가 알려 준 결제수단을 충전 주문에 기록한다")
    fun confirm_approved_recordsPaymentMethod() {
        val paid: ChargeOrderConfirmResponse = ChargeOrderConfirmResponse("chg_order", 1_000L, ChargeOrderStatus.PAID)
        val service: ChargeOrderConfirmService =
            approvedFlow(
                PgConfirmationResult.approved(
                    "payment-key",
                    "간편결제",
                    "토스페이",
                ),
                paid,
            )

        val response: ChargeOrderConfirmResponse = service.confirm(7L, "payment-key", "chg_order", 1_000L, "client-key")

        assertThat(response).isEqualTo(paid)
        verify(transactionService).recordPaymentMethod("chg_order", "간편결제", "토스페이")
    }

    @Test
    @DisplayName("결제수단 기록이 실패해도 충전 결과(PAID)는 그대로 반환한다")
    fun confirm_paymentMethodRecordFailure_doesNotAffectCharge() {
        val paid: ChargeOrderConfirmResponse = ChargeOrderConfirmResponse("chg_order", 1_000L, ChargeOrderStatus.PAID)
        val service: ChargeOrderConfirmService =
            approvedFlow(
                PgConfirmationResult.approved(
                    "payment-key",
                    "카드",
                    null,
                ),
                paid,
            )
        willThrow(IllegalStateException("db down"))
            .given(transactionService)
            .recordPaymentMethod("chg_order", "카드", null)

        val response: ChargeOrderConfirmResponse = service.confirm(7L, "payment-key", "chg_order", 1_000L, "client-key")

        assertThat(response).isEqualTo(paid)
    }

    @Test
    @DisplayName("PG가 결제수단을 주지 않으면 기록 단계를 건너뛴다")
    fun confirm_withoutPaymentMethod_skipsRecording() {
        val paid: ChargeOrderConfirmResponse = ChargeOrderConfirmResponse("chg_order", 1_000L, ChargeOrderStatus.PAID)
        val service: ChargeOrderConfirmService = approvedFlow(PgConfirmationResult.approved("payment-key"), paid)

        service.confirm(7L, "payment-key", "chg_order", 1_000L, "client-key")

        verify(transactionService, never()).recordPaymentMethod(
            anyNonNull(),
            anyNonNull(),
            org.mockito.ArgumentMatchers.any(),
        )
    }

    @Test
    @DisplayName("PG 거절 결과에 오류 코드가 없으면 주문 상태를 변경하지 않고 즉시 실패한다")
    fun confirm_declinedWithoutErrorCode_failsFastWithoutStateChange() {
        val pgRequest: PgConfirmRequest = PgConfirmRequest("payment-key", "chg_order", 1_000L, "pg-key")
        given(transactionService.prepare(7L, "payment-key", "chg_order", 1_000L, "client-key"))
            .willReturn(ChargeOrderPreparation.callPg(pgRequest))
        given(pgClient.confirm(pgRequest)).willReturn(
            PgConfirmationResult(
                PgConfirmationResult.Type.DECLINED,
                null,
                null,
                null,
                null,
                null,
            ),
        )
        val service: ChargeOrderConfirmService =
            ChargeOrderConfirmService(
                transactionService,
                pgClient,
                idempotencyService,
            )

        assertThatThrownBy({
            service.confirm(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "client-key",
            )
        })
            .isInstanceOf(NullPointerException::class.java)
            .hasMessage("PG 거절 결과에는 오류 코드가 필요합니다.")
        verify(transactionService, never()).completeDeclined("chg_order", nullValue())
    }

    @Test
    @DisplayName("PG 승인 뒤 DB 2 경합으로 현재 상태가 FAILED면 200 응답 대신 상태 충돌을 반환한다")
    fun confirm_db2StateConflictWithFailedCurrentState_throwsStateConflict() {
        val pgRequest: PgConfirmRequest =
            PgConfirmRequest(
                "payment-key",
                "chg_order",
                1_000L,
                "pg-key",
            )
        val failed: ChargeOrderConfirmResponse =
            ChargeOrderConfirmResponse(
                "chg_order",
                1_000L,
                ChargeOrderStatus.FAILED,
            )
        given(
            transactionService.prepare(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "client-key",
            ),
        ).willReturn(ChargeOrderPreparation.callPg(pgRequest))
        given(pgClient.confirm(pgRequest))
            .willReturn(PgConfirmationResult.approved("payment-key"))
        given(
            transactionService.completeApproved(
                "chg_order",
                "payment-key",
                "client-key",
            ),
        ).willThrow(BusinessException(ErrorCode.CHARGE_ORDER_STATE_CONFLICT))
        given(transactionService.currentResponse("chg_order")).willReturn(failed)
        val service: ChargeOrderConfirmService =
            ChargeOrderConfirmService(
                transactionService,
                pgClient,
                idempotencyService,
            )

        assertThatThrownBy({
            service.confirm(
                7L,
                "payment-key",
                "chg_order",
                1_000L,
                "client-key",
            )
        })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.CHARGE_ORDER_STATE_CONFLICT,
            )
    }

    @Test
    @DisplayName("승인 결과 확인 조회가 불확실하면 주문번호와 조회 분류를 경고로 남긴다")
    fun confirm_lookupUnknown_logsOrderIdAndClassification(output: CapturedOutput) {
        val pgRequest: PgConfirmRequest =
            PgConfirmRequest(
                "payment-key-sensitive",
                "chg_order",
                1_000L,
                "pg-key",
            )
        val confirming: ChargeOrderConfirmResponse =
            ChargeOrderConfirmResponse(
                "chg_order",
                1_000L,
                ChargeOrderStatus.CONFIRMING,
            )
        given(
            transactionService.prepare(
                7L,
                "payment-key-sensitive",
                "chg_order",
                1_000L,
                "client-key",
            ),
        ).willReturn(ChargeOrderPreparation.callPg(pgRequest), ChargeOrderPreparation.current(confirming))
        given(pgClient.confirm(pgRequest)).willReturn(
            PgConfirmationResult.unknown(
                "ALREADY_PROCESSED_PAYMENT",
                PgConfirmationResult.UnknownReason.ALREADY_PROCESSED,
            ),
        )
        given(pgClient.findByPaymentKey("payment-key-sensitive"))
            .willReturn(PgPaymentLookupResult.unknown())
        val service: ChargeOrderConfirmService =
            ChargeOrderConfirmService(
                transactionService,
                pgClient,
                idempotencyService,
            )

        val response: ChargeOrderConfirmResponse =
            service.confirm(
                7L,
                "payment-key-sensitive",
                "chg_order",
                1_000L,
                "client-key",
            )

        assertThat(response).isEqualTo(confirming)
        assertThat(output)
            .contains("orderId=chg_order")
            .contains("lookupStatus=UNKNOWN")
            .doesNotContain("payment-key-sensitive")
    }
}
