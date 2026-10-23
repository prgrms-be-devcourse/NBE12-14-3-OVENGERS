package com.ovengers.slotkey.credit.webhook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 웹훅 수신 기록(pg_webhook_event). 수신 경로에서는 이 기록 한 줄만 쓰고 주문·원장·잔액은 건드리지 않는다.
 *
 * `INSERT IGNORE`를 쓰는 이유는 {@code ProcessedEventStore}와 같다: 중복 키를 예외로 받지 않고
 * 영향 행 수(1=처음, 0=이미 받은 알림)로 판정한다. UNIQUE(transmission_id)가 동시 중복도 한 행으로 막는다.
 */
@Component
public class PgWebhookEventStore {

    static final String RECEIVED = "RECEIVED";
    static final String DONE = "DONE";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public PgWebhookEventStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public boolean chargeOrderExists(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charge_order WHERE order_id = ?",
                Integer.class,
                orderId
        );
        return count != null && count > 0;
    }

    /** 처음 받은 알림이면 true, 같은 transmission_id를 이미 기록했으면 false. */
    public boolean recordReceived(PgWebhookEvent event) {
        return jdbcTemplate.update(
                "INSERT IGNORE INTO pg_webhook_event "
                        + "(transmission_id, event_type, order_id, payment_key, reported_status, "
                        + "retried_count, status, received_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                event.transmissionId(),
                event.eventType(),
                event.orderId(),
                event.paymentKey(),
                event.reportedStatus(),
                event.retriedCount(),
                RECEIVED,
                LocalDateTime.now(clock)
        ) == 1;
    }

    /** 처리기가 읽을 RECEIVED 행 id를 오래된 순서로 돌려준다(status, id 인덱스). */
    public List<Long> findReceivedIds(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM pg_webhook_event WHERE status = ? ORDER BY id LIMIT ?",
                Long.class,
                RECEIVED,
                limit
        );
    }

    /** 아직 처리하지 않은 행의 주문번호. 이미 DONE이거나 없으면 null이다. */
    public String findReceivedOrderId(Long eventId) {
        return jdbcTemplate.queryForList(
                        "SELECT order_id FROM pg_webhook_event WHERE id = ? AND status = ?",
                        String.class,
                        eventId,
                        RECEIVED
                )
                .stream()
                .findFirst()
                .orElse(null);
    }

    /** RECEIVED인 행만 DONE으로 바꾼다. 겹친 처리에서 먼저 기록한 결과를 덮어쓰지 않는다. */
    public void markDone(Long eventId, PgWebhookEventResult result) {
        jdbcTemplate.update(
                "UPDATE pg_webhook_event SET status = ?, result = ?, processed_at = ? "
                        + "WHERE id = ? AND status = ?",
                DONE,
                result.name(),
                LocalDateTime.now(clock),
                eventId,
                RECEIVED
        );
    }
}
