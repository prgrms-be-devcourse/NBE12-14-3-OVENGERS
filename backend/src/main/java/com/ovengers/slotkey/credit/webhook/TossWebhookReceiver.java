package com.ovengers.slotkey.credit.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


/**
 * 토스 웹훅 수신: 검사 후 기록 한 줄만 남기고 끝낸다. PG 호출과 충전 확정은 하지 않는다
 * (docs/decisions/pg-charge.md 5단계 결정 3).
 */
@Service
public class TossWebhookReceiver {

    static final String PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED";

    private static final Logger log = LoggerFactory.getLogger(TossWebhookReceiver.class);
    private static final int MAX_TRANSMISSION_ID_LENGTH = 100;
    private static final int MAX_ORDER_ID_LENGTH = 64;
    private static final int MAX_PAYMENT_KEY_LENGTH = 200;
    private static final int MAX_STATUS_LENGTH = 30;

    private final PgWebhookEventStore eventStore;
    private final ObjectMapper objectMapper;

    public TossWebhookReceiver(PgWebhookEventStore eventStore, ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
    }

    public void receive(String transmissionId, String retriedCount, String body) {
        if (transmissionId == null
                || transmissionId.isBlank()
                || transmissionId.length() > MAX_TRANSMISSION_ID_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "웹훅 식별자가 올바르지 않습니다.");
        }
        JsonNode root = parse(body);

        String eventType = text(root.path("eventType"), Integer.MAX_VALUE);
        if (!PAYMENT_STATUS_CHANGED.equals(eventType)) {
            log.info("처리 대상이 아닌 웹훅을 무시합니다. eventType={}", abbreviate(eventType));
            return;
        }
        JsonNode data = root.path("data");
        String orderId = text(data.path("orderId"), MAX_ORDER_ID_LENGTH);
        if (orderId == null) {
            log.warn("주문번호가 없거나 형식이 맞지 않는 웹훅을 무시합니다. eventType={}", eventType);
            return;
        }
        if (!eventStore.chargeOrderExists(orderId)) {
            log.warn("충전 주문이 없는 웹훅을 무시합니다. orderId={}", orderId);
            return;
        }

        boolean recorded = eventStore.recordReceived(new PgWebhookEvent(
                transmissionId,
                eventType,
                orderId,
                text(data.path("paymentKey"), MAX_PAYMENT_KEY_LENGTH),
                text(data.path("status"), MAX_STATUS_LENGTH),
                parseRetriedCount(retriedCount)
        ));
        log.info(
                "웹훅을 수신했습니다. orderId={} eventType={} result={}",
                orderId,
                eventType,
                recorded ? "RECORDED" : "DUPLICATE_IGNORED"
        );
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "웹훅 본문이 없습니다.");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "웹훅 본문이 JSON 객체가 아닙니다.");
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "웹훅 본문이 JSON이 아닙니다.");
        }
    }

    /** 문자열이 아니거나 비었거나 길이를 넘으면 null. */
    private String text(JsonNode node, int maxLength) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        if (value.isBlank() || value.length() > maxLength) {
            return null;
        }
        return value;
    }

    private Integer parseRetriedCount(String retriedCount) {
        if (retriedCount == null || retriedCount.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(retriedCount.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 50 ? value.substring(0, 50) : value;
    }
}
