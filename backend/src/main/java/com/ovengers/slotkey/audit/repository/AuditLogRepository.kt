package com.ovengers.slotkey.audit.repository

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface AuditLogRepository : JpaRepository<AuditLog, Long> {
    fun findFirstByTargetTypeAndTargetIdAndActionInOrderByCreatedAtDescIdDesc(
        targetType: AuditTargetType,
        targetId: Long,
        actions: Collection<AuditAction>,
    ): Optional<AuditLog>

    fun countByTargetTypeAndTargetIdAndActionIn(
        targetType: AuditTargetType,
        targetId: Long,
        actions: Collection<AuditAction>,
    ): Long

    @Query(
        value = """
            SELECT a FROM AuditLog a
            WHERE (:actorMemberId IS NULL OR a.actorMemberId = :actorMemberId)
              AND (:action IS NULL OR a.action = :action)
              AND (:targetType IS NULL OR a.targetType = :targetType)
              AND (:targetId IS NULL OR a.targetId = :targetId)
              AND (:fromInclusive IS NULL OR a.createdAt >= :fromInclusive)
              AND (:toExclusive IS NULL OR a.createdAt < :toExclusive)
        """,
        countQuery = """
            SELECT COUNT(a) FROM AuditLog a
            WHERE (:actorMemberId IS NULL OR a.actorMemberId = :actorMemberId)
              AND (:action IS NULL OR a.action = :action)
              AND (:targetType IS NULL OR a.targetType = :targetType)
              AND (:targetId IS NULL OR a.targetId = :targetId)
              AND (:fromInclusive IS NULL OR a.createdAt >= :fromInclusive)
              AND (:toExclusive IS NULL OR a.createdAt < :toExclusive)
        """,
    )
    fun searchAuditLogs(
        @Param("actorMemberId") actorMemberId: Long?,
        @Param("action") action: AuditAction?,
        @Param("targetType") targetType: AuditTargetType?,
        @Param("targetId") targetId: Long?,
        @Param("fromInclusive") fromInclusive: LocalDateTime?,
        @Param("toExclusive") toExclusive: LocalDateTime?,
        pageable: Pageable?,
    ): Page<AuditLog>

    fun findByTargetTypeAndTargetIdAndActionIn(
        targetType: AuditTargetType,
        targetId: Long,
        actions: Collection<AuditAction>,
        pageable: Pageable,
    ): Page<AuditLog>
}
