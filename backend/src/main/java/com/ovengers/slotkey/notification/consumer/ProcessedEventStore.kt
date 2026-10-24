package com.ovengers.slotkey.notification.consumer

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 컨슈머 멱등 기록(processed_event). 호출자 트랜잭션 안에서 INSERT하므로 알림 저장이 롤백되면 이 기록도 사라진다.
 * `INSERT IGNORE`의 영향 행 수(1=처음, 0=이미 처리함)로 판정한다.
 */
@Component
class ProcessedEventStore(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {
    /** 처음 보는 이벤트면 true, 이미 처리한 이벤트면 false. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun markProcessed(
        consumer: String,
        eventId: String,
    ): Boolean =
        jdbcTemplate.update(
            "INSERT IGNORE INTO processed_event (consumer, event_id, processed_at) VALUES (?, ?, ?)",
            consumer,
            eventId,
            LocalDateTime.now(clock),
        ) == 1
}
