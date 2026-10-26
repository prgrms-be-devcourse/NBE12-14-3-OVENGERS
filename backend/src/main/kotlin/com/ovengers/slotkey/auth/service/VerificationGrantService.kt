
package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.entity.VerificationGrant
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.repository.VerificationGrantRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class VerificationGrantService(
    private val guardService: VerificationGuardService,
    private val grantRepository: VerificationGrantRepository,
    private val secretHasher: VerificationSecretHasher,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun requireUsable(
        email: String,
        purpose: VerificationPurpose,
        rawGrant: String?,
    ): VerificationGrant {
        if (rawGrant.isNullOrBlank()) {
            throw invalidGrant()
        }

        // 발급 및 검증과 동일한 Guard를 잠가 동시 처리를 직렬화
        val guard = guardService.acquire(email, purpose)

        val grant =
            grantRepository
                .findByGrantHash(secretHasher.hashGrant(rawGrant))
                .orElseThrow(::invalidGrant)

        // 잠금 대기 중 만료될 수 있으므로 락 획득 이후 시각 확인
        val now = LocalDateTime.now(clock)

        if (
            grant.challenge.guard.id != guard.id ||
            !grant.isUsable(now)
        ) {
            throw invalidGrant()
        }

        return grant
    }

    private fun invalidGrant(): BusinessException = BusinessException(ErrorCode.VERIFICATION_GRANT_INVALID)
}
