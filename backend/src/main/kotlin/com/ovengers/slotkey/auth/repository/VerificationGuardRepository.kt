
package com.ovengers.slotkey.auth.repository

import com.ovengers.slotkey.auth.entity.VerificationGuard
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface VerificationGuardRepository : JpaRepository<VerificationGuard, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM VerificationGuard g WHERE g.email = :email")
    fun findAllByEmailForUpdate(
        @Param("email") email: String,
    ): List<VerificationGuard>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT g
        FROM VerificationGuard g
        WHERE g.email = :email
          AND g.purpose = :purpose
        """,
    )
    fun findForUpdate(
        @Param("email") email: String,
        @Param("purpose") purpose: VerificationPurpose,
    ): Optional<VerificationGuard>

    @Modifying
    @Query(
        value = """
            INSERT INTO verification_guard (
                email,
                purpose,
                failed_attempts,
                block_stage,
                admin_locked
            )
            VALUES (
                :email,
                :purpose,
                0,
                0,
                FALSE
            )
            ON DUPLICATE KEY UPDATE id = id
        """,
        nativeQuery = true,
    )
    fun ensureExists(
        @Param("email") email: String,
        @Param("purpose") purpose: String,
    )
}
