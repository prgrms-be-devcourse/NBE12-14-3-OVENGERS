package com.ovengers.slotkey.credit.repository

import com.ovengers.slotkey.credit.entity.ChargeOrder
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
@JvmSuppressWildcards
interface ChargeOrderRepository : JpaRepository<ChargeOrder, Long> {
    fun findByOrderId(orderId: String): ChargeOrder?

    /** 내 충전 내역(최신순). 상태를 직접 넘겨 미결제 시도(PENDING)를 제외한다. */
    fun findByMemberIdAndStatusInOrderByIdDesc(
        memberId: Long?,
        statuses: Collection<ChargeOrderStatus>,
        pageable: Pageable,
    ): Page<ChargeOrder>

    fun findByMemberIdAndClientIdempotencyKey(
        memberId: Long?,
        clientIdempotencyKey: String,
    ): ChargeOrder?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ChargeOrder c WHERE c.orderId = :orderId")
    fun findByOrderIdForUpdate(
        @Param("orderId") orderId: String,
    ): ChargeOrder?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ChargeOrder c WHERE c.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long?,
    ): ChargeOrder?

    @Query(
        """
        SELECT c.id
        FROM ChargeOrder c
        WHERE c.status = :status
          AND c.recoveryExhaustedAt IS NULL
          AND (c.recoveryNextAttemptAt IS NULL OR c.recoveryNextAttemptAt <= :now)
          AND c.updatedAt > :confirmingStartedAfter
        ORDER BY c.recoveryNextAttemptAt ASC, c.id ASC
        """,
    )
    fun findRecoveryCandidateIds(
        @Param("status") status: ChargeOrderStatus,
        @Param("now") now: LocalDateTime,
        @Param("confirmingStartedAfter") confirmingStartedAfter: LocalDateTime,
        pageable: Pageable,
    ): List<Long>

    @Query(
        """
        SELECT c.id
        FROM ChargeOrder c
        WHERE c.status = :status
          AND c.recoveryExhaustedAt IS NULL
          AND c.updatedAt <= :confirmingStartedAtOrBefore
        ORDER BY c.updatedAt ASC, c.id ASC
        """,
    )
    fun findRecoveryExhaustedCandidateIds(
        @Param("status") status: ChargeOrderStatus,
        @Param("confirmingStartedAtOrBefore") confirmingStartedAtOrBefore: LocalDateTime,
        pageable: Pageable,
    ): List<Long>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE ChargeOrder c
        SET c.status = :confirming,
            c.paymentKey = :paymentKey,
            c.clientIdempotencyKey = :clientIdempotencyKey,
            c.pgIdempotencyKey = :pgIdempotencyKey,
            c.recoveryNextAttemptAt = :recoveryNextAttemptAt,
            c.recoveryExhaustedAt = NULL,
            c.updatedAt = :now
        WHERE c.id = :id AND c.status = :pending
        """,
    )
    fun startConfirming(
        @Param("id") id: Long?,
        @Param("pending") pending: ChargeOrderStatus,
        @Param("confirming") confirming: ChargeOrderStatus,
        @Param("paymentKey") paymentKey: String,
        @Param("clientIdempotencyKey") clientIdempotencyKey: String,
        @Param("pgIdempotencyKey") pgIdempotencyKey: String,
        @Param("now") now: LocalDateTime,
        @Param("recoveryNextAttemptAt") recoveryNextAttemptAt: LocalDateTime,
    ): Int
}
