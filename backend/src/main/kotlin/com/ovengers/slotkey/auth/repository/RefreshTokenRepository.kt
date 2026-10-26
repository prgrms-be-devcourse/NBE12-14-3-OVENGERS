
package com.ovengers.slotkey.auth.repository

import com.ovengers.slotkey.auth.entity.RefreshToken
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.Optional

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {
    fun findByTokenHash(tokenHash: String): Optional<RefreshToken>

    @Query(
        """
        SELECT t FROM RefreshToken t
        JOIN FETCH t.member
        JOIN FETCH t.authSession
        WHERE t.tokenHash = :tokenHash
        """,
    )
    fun findWithSessionByTokenHash(
        @Param("tokenHash") tokenHash: String,
    ): Optional<RefreshToken>

    @Query(
        """
        SELECT t.id AS id,
               t.member.id AS memberId,
               t.authSession.id AS sessionId
        FROM RefreshToken t
        WHERE t.tokenHash = :tokenHash
        """,
    )
    fun findReferenceByTokenHash(
        @Param("tokenHash") tokenHash: String,
    ): Optional<RefreshTokenReference>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.id = :tokenId")
    fun findByIdForUpdate(
        @Param("tokenId") tokenId: Long,
    ): Optional<RefreshToken>

    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE RefreshToken t
        SET t.revokedAt = :now
        WHERE t.authSession.id = :sessionId
          AND t.revokedAt IS NULL
        """,
    )
    fun revokeAllBySessionId(
        @Param("sessionId") sessionId: String,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE RefreshToken t
        SET t.revokedAt = :now
        WHERE t.member.id = :memberId
          AND t.revokedAt IS NULL
        """,
    )
    fun revokeAllByMemberId(
        @Param("memberId") memberId: Long,
        @Param("now") now: LocalDateTime,
    ): Int

    interface RefreshTokenReference {
        fun getId(): Long

        fun getMemberId(): Long

        fun getSessionId(): String
    }
}
