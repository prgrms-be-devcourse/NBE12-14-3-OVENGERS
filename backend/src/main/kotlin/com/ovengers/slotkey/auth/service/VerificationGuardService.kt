
package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.entity.VerificationGuard
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.repository.VerificationGuardRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class VerificationGuardService(
    private val guardRepository: VerificationGuardRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun acquire(
        email: String,
        purpose: VerificationPurpose,
    ): VerificationGuard {
        guardRepository.ensureExists(email, purpose.name)

        return guardRepository
            .findForUpdate(email, purpose)
            .orElseThrow {
                IllegalStateException("인증 상태 생성에 실패했습니다.")
            }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun unlockAllForEmail(email: String) {
        guardRepository
            .findAllByEmailForUpdate(email)
            .forEach(VerificationGuard::unlockByAdmin)
    }
}
