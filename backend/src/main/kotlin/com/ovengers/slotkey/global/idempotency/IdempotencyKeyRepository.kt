package com.ovengers.slotkey.global.idempotency

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface IdempotencyKeyRepository : JpaRepository<IdempotencyKey, Long> {
    fun findByKeyAndMemberIdAndRequestPath(
        key: String,
        memberId: Long,
        requestPath: String,
    ): Optional<IdempotencyKey>
}
