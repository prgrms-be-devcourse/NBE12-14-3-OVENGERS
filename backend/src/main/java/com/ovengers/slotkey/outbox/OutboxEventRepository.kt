package com.ovengers.slotkey.outbox

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {
    fun findByStatusOrderByIdAsc(
        status: String,
        pageable: Pageable,
    ): List<OutboxEvent>

    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = 'PUBLISHED', e.publishedAt = :now WHERE e.id = :id AND e.status = 'PENDING'")
    fun markPublished(
        @Param("id") id: Long?,
        @Param("now") now: LocalDateTime?,
    ): Int

    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.attempts = e.attempts + 1 WHERE e.id = :id AND e.status = 'PENDING'")
    fun increaseAttempts(
        @Param("id") id: Long?,
    ): Int
}
