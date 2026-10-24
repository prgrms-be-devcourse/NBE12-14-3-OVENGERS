package com.ovengers.slotkey.notification.repository

import com.ovengers.slotkey.notification.entity.Notification
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface NotificationRepository : JpaRepository<Notification, Long> {
    fun findAllByMemberIdOrderByIdDesc(
        memberId: Long,
        pageable: Pageable,
    ): Page<Notification>

    fun countByMemberIdAndReadAtIsNull(memberId: Long): Long

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :readAt WHERE n.memberId = :memberId AND n.readAt IS NULL AND n.id <= :upToId")
    fun markAllRead(
        @Param("memberId") memberId: Long,
        @Param("upToId") upToId: Long,
        @Param("readAt") readAt: LocalDateTime,
    ): Int
}
