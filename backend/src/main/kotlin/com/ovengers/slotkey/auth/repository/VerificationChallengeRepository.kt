
package com.ovengers.slotkey.auth.repository

import com.ovengers.slotkey.auth.entity.VerificationChallenge
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface VerificationChallengeRepository : JpaRepository<VerificationChallenge, Long> {
    fun findFirstByGuardIdOrderByIdDesc(guardId: Long): Optional<VerificationChallenge>

    fun findAllByGuardIdAndVerifiedAtIsNullAndInvalidatedAtIsNull(guardId: Long): List<VerificationChallenge>
}
