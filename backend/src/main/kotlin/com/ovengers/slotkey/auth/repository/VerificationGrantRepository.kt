
package com.ovengers.slotkey.auth.repository

import com.ovengers.slotkey.auth.entity.VerificationGrant
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface VerificationGrantRepository : JpaRepository<VerificationGrant, Long> {
    fun findByGrantHash(grantHash: String): Optional<VerificationGrant>

    @Suppress("ktlint:standard:function-naming")
    fun findAllByChallenge_Guard_IdAndConsumedAtIsNullAndInvalidatedAtIsNull(guardId: Long): List<VerificationGrant>
}
