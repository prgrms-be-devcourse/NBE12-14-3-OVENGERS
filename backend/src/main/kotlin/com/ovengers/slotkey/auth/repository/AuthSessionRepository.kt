
package com.ovengers.slotkey.auth.repository

import com.ovengers.slotkey.auth.entity.AuthSession
import com.ovengers.slotkey.member.entity.MemberStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.Optional

interface AuthSessionRepository : JpaRepository<AuthSession, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AuthSession s WHERE s.id = :sessionId")
    fun findByIdForUpdate(
        @Param("sessionId") sessionId: String,
    ): Optional<AuthSession>

    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE AuthSession s
        SET s.revokedAt = :now
        WHERE s.member.id = :memberId
          AND s.revokedAt IS NULL
        """,
    )
    fun revokeAllByMemberId(
        @Param("memberId") memberId: Long,
        @Param("now") now: LocalDateTime,
    ): Int

    @Query(
        """
        SELECT (COUNT(s) > 0)
        FROM AuthSession s JOIN s.member m
        WHERE s.id = :sessionId
          AND s.revokedAt IS NULL
          AND s.expiresAt > :now
          AND m.id = :memberId
          AND m.status = :activeStatus
        """,
    )
    fun isUsable(
        @Param("sessionId") sessionId: String,
        @Param("memberId") memberId: Long,
        @Param("now") now: LocalDateTime,
        @Param("activeStatus") activeStatus: MemberStatus,
    ): Boolean

    // 세션 소유자가 일치하고 만료되지 않은 경우 현재 회원 상태 조회
    // 정지 처리 시 세션도 폐기되므로 revokedAt 조건은 적용하지 않음
    @Query(
        """
        SELECT m.status
        FROM AuthSession s JOIN s.member m
        WHERE s.id = :sessionId
          AND m.id = :memberId
          AND s.expiresAt > :now
        """,
    )
    fun findMemberStatusForAccessAttempt(
        @Param("sessionId") sessionId: String,
        @Param("memberId") memberId: Long,
        @Param("now") now: LocalDateTime,
    ): Optional<MemberStatus>
}
