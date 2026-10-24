package com.ovengers.slotkey.credit.webhook

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 토스 웹훅 수신: 검사 후 기록 한 줄만 남기고 끝낸다. PG 호출과 충전 확정은 하지 않는다
 * (docs/decisions/pg-charge.md 5단계 결정 3).
 */
@Service
class TossWebhookReceiver(
    private val eventStore: PgWebhookEventStore,
    private val objectMapper: ObjectMapper,
) {
    fun receive(
        transmissionId: String?,
        retriedCount: String?,
        body: String?,
    ) {
        if (
            transmissionId.isNullOrBlank() ||
            transmissionId.length > MAX_TRANSMISSION_ID_LENGTH
        ) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "웹훅 식별자가 올바르지 않습니다.")
        }
        val root = parse(body)

        val eventType = text(root.path("eventType"), Int.MAX_VALUE)
        if (eventType != PAYMENT_STATUS_CHANGED) {
            log.info("처리 대상이 아닌 웹훅을 무시합니다. eventType={}", abbreviate(eventType))
            return
        }
        val data = root.path("data")
        val orderId = text(data.path("orderId"), MAX_ORDER_ID_LENGTH)
        if (orderId == null) {
            log.warn("주문번호가 없거나 형식이 맞지 않는 웹훅을 무시합니다. eventType={}", eventType)
            return
        }
        if (!eventStore.chargeOrderExists(orderId)) {
            log.warn("충전 주문이 없는 웹훅을 무시합니다. orderId={}", orderId)
            return
        }

        val recorded =
            eventStore.recordReceived(
                PgWebhookEvent(
                    transmissionId,
                    eventType,
                    orderId,
                    text(data.path("paymentKey"), MAX_PAYMENT_KEY_LENGTH),
                    text(data.path("status"), MAX_STATUS_LENGTH),
                    parseRetriedCount(retriedCount),
                ),
            )
        log.info(
            "웹훅을 수신했습니다. orderId={} eventType={} result={}",
            orderId,
            eventType,
            if (recorded) "RECORDED" else "DUPLICATE_IGNORED",
        )
    }

    private fun parse(body: String?): JsonNode {
        if (body.isNullOrBlank()) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "웹훅 본문이 없습니다.")
        }
        return try {
            val root = objectMapper.readTree(body)
            if (root == null || !root.isObject) {
                throw BusinessException(ErrorCode.VALIDATION_FAILED, "웹훅 본문이 JSON 객체가 아닙니다.")
            }
            root
        } catch (exception: JsonProcessingException) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "웹훅 본문이 JSON이 아닙니다.")
        }
    }

    /** 문자열이 아니거나 비었거나 길이를 넘으면 null. */
    private fun text(
        node: JsonNode?,
        maxLength: Int,
    ): String? {
        if (node == null || !node.isTextual) return null
        val value = node.asText()
        return value.takeIf { it.isNotBlank() && it.length <= maxLength }
    }

    private fun parseRetriedCount(retriedCount: String?): Int? {
        if (retriedCount.isNullOrBlank()) return null
        return retriedCount.trim().toIntOrNull()
    }

    private fun abbreviate(value: String?): String? =
        when {
            value == null -> null
            value.length > 50 -> value.substring(0, 50)
            else -> value
        }

    companion object {
        const val PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED"

        private val log = LoggerFactory.getLogger(TossWebhookReceiver::class.java)
        private const val MAX_TRANSMISSION_ID_LENGTH = 100
        private const val MAX_ORDER_ID_LENGTH = 64
        private const val MAX_PAYMENT_KEY_LENGTH = 200
        private const val MAX_STATUS_LENGTH = 30
    }
}
