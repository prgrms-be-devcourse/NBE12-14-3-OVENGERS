package com.ovengers.slotkey.waitlist.repository

import com.ovengers.slotkey.waitlist.entity.WaitlistEntry
import com.ovengers.slotkey.waitlist.entity.WaitlistStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface WaitlistEntryRepository : JpaRepository<WaitlistEntry, Long> {
    fun countByMemberIdAndStatusAndStartTimeAfter(
        memberId: Long,
        status: WaitlistStatus,
        now: LocalDateTime,
    ): Long

    fun findAllByMemberIdAndStatusAndStartTimeAfterOrderByIdAsc(
        memberId: Long,
        status: WaitlistStatus,
        now: LocalDateTime,
    ): List<WaitlistEntry>

    @Query(
        "SELECT COUNT(w) FROM WaitlistEntry w " +
            "WHERE w.spaceId = :spaceId AND w.status = :status AND w.startTime > :now " +
            "AND w.startTime < :endTime AND w.endTime > :startTime " +
            "AND w.id < :id",
    )
    fun countOverlappingAhead(
        @Param("spaceId") spaceId: Long,
        @Param("startTime") startTime: LocalDateTime,
        @Param("endTime") endTime: LocalDateTime,
        @Param("id") id: Long,
        @Param("status") status: WaitlistStatus,
        @Param("now") now: LocalDateTime,
    ): Long

    @Modifying
    @Query("UPDATE WaitlistEntry w SET w.lastNotifiedAt = :now WHERE w.id = :id AND w.status = :status")
    fun markNotified(
        @Param("id") id: Long,
        @Param("now") now: LocalDateTime,
        @Param("status") status: WaitlistStatus,
    ): Int

    @Query(
        "SELECT w FROM WaitlistEntry w " +
            "WHERE w.spaceId = :spaceId AND w.status = :status AND w.startTime > :now " +
            "AND w.startTime < :endTime AND w.endTime > :startTime " +
            "ORDER BY w.id",
    )
    fun findNotificationCandidates(
        @Param("spaceId") spaceId: Long,
        @Param("startTime") startTime: LocalDateTime,
        @Param("endTime") endTime: LocalDateTime,
        @Param("status") status: WaitlistStatus,
        @Param("now") now: LocalDateTime,
    ): List<WaitlistEntry>
}
