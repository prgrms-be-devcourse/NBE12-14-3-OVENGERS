package com.ovengers.slotkey.auth.ratelimit

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class JdbcLoginAttemptStore(
    private val jdbcTemplate: JdbcTemplate,
) : LoginAttemptStore {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun tryAcquire(
        email: String,
        windowId: Long,
        limit: Int,
    ): Boolean {
        jdbcTemplate.update(
            """
            INSERT INTO login_attempt_bucket (email, window_id, attempt_count)
            VALUES (?, ?, 0)
            ON DUPLICATE KEY UPDATE attempt_count = attempt_count
            """,
            email,
            windowId,
        )

        val attemptCount: Int =
            jdbcTemplate.queryForObject(
                """
                SELECT attempt_count
                FROM login_attempt_bucket
                WHERE email = ? AND window_id = ?
                FOR UPDATE
                """,
                Int::class.java,
                email,
                windowId,
            ) ?: throw DataAccessResourceFailureException(
                "Login rate-limit bucket could not be read after creation",
            )

        if (attemptCount >= limit) return false

        jdbcTemplate.update(
            """
            UPDATE login_attempt_bucket
            SET attempt_count = attempt_count + 1
            WHERE email = ? AND window_id = ?
            """,
            email,
            windowId,
        )
        return true
    }
}
