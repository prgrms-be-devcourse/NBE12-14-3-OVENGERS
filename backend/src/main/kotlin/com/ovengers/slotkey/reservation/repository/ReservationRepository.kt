package com.ovengers.slotkey.reservation.repository

import com.ovengers.slotkey.reservation.dto.ReservationTargetInfo
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
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
import java.util.Optional

@Repository
@JvmSuppressWildcards
interface ReservationRepository : JpaRepository<Reservation, Long> {
    /** 결제 확인의 문지기. HELD이고 아직 만료 전일 때만 CONFIRMED로 전이한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Reservation r SET r.status = :confirmed " +
            "WHERE r.id = :id AND r.status = :held AND :now < r.holdExpiresAt",
    )
    fun confirmIfHeldAndNotExpired(
        @Param("id") id: Long?,
        @Param("now") now: LocalDateTime,
        @Param("held") held: ReservationStatus,
        @Param("confirmed") confirmed: ReservationStatus,
    ): Int

    /** 만료된 HELD를 예약 생성 시점에도 정리한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Reservation r SET r.status = :expired " +
            "WHERE r.id IN :ids AND r.status = :held AND r.holdExpiresAt <= :now",
    )
    fun expireHeldReservations(
        @Param("ids") ids: List<Long>,
        @Param("now") now: LocalDateTime,
        @Param("held") held: ReservationStatus,
        @Param("expired") expired: ReservationStatus,
    ): Int

    /** CONFIRMED이고 아직 시작 전일 때만 CANCELLED로 전이한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Reservation r SET r.status = :cancelled, r.cancelledAt = :now " +
            "WHERE r.id = :id AND r.status = :confirmed AND :now < r.startTime",
    )
    fun cancelIfConfirmedAndBeforeStart(
        @Param("id") id: Long?,
        @Param("now") now: LocalDateTime,
        @Param("confirmed") confirmed: ReservationStatus,
        @Param("cancelled") cancelled: ReservationStatus,
    ): Int

    /** HELD일 때만 CANCELLED로 전이한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Reservation r SET r.status = :cancelled, r.cancelledAt = :now " +
            "WHERE r.id = :id AND r.status = :held",
    )
    fun cancelIfHeld(
        @Param("id") id: Long?,
        @Param("now") now: LocalDateTime,
        @Param("held") held: ReservationStatus,
        @Param("cancelled") cancelled: ReservationStatus,
    ): Int

    /** 조회 시점의 상태가 그대로일 때만 관리자 강제 취소를 적용한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Reservation r SET r.status = :cancelled, r.cancelledAt = :now " +
            "WHERE r.id = :id AND r.status = :expected",
    )
    fun forceCancelIfStatusIs(
        @Param("id") id: Long?,
        @Param("now") now: LocalDateTime,
        @Param("expected") expected: ReservationStatus,
        @Param("cancelled") cancelled: ReservationStatus,
    ): Int

    /** CONFIRMED일 때만 IN_USE로 전이하고 checked_in_at을 기록한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Reservation r SET r.status = :inUse, r.checkedInAt = :now " +
            "WHERE r.id = :id AND r.status = :confirmed",
    )
    fun checkInIfConfirmed(
        @Param("id") id: Long?,
        @Param("now") now: LocalDateTime,
        @Param("confirmed") confirmed: ReservationStatus,
        @Param("inUse") inUse: ReservationStatus,
    ): Int

    /** IN_USE일 때만 COMPLETED로 전이한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Reservation r SET r.status = :completed, r.checkedOutAt = :checkedOutAt " +
            "WHERE r.id = :id AND r.status = :inUse",
    )
    fun checkOutIfInUse(
        @Param("id") id: Long?,
        @Param("checkedOutAt") checkedOutAt: LocalDateTime,
        @Param("inUse") inUse: ReservationStatus,
        @Param("completed") completed: ReservationStatus,
    ): Int

    /** 종료 시각 낙관적 검사와 상태 검사를 한 번에 수행한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Reservation r SET r.endTime = :newEndTime, r.totalAmount = :newTotalAmount " +
            "WHERE r.id = :id AND r.endTime = :expectedEndTime " +
            "AND r.status IN (:confirmed, :inUse)",
    )
    fun extendIfEndTimeMatches(
        @Param("id") id: Long?,
        @Param("expectedEndTime") expectedEndTime: LocalDateTime,
        @Param("newEndTime") newEndTime: LocalDateTime,
        @Param("newTotalAmount") newTotalAmount: Int?,
        @Param("confirmed") confirmed: ReservationStatus,
        @Param("inUse") inUse: ReservationStatus,
    ): Int

    fun findAllByMemberId(
        memberId: Long?,
        pageable: Pageable,
    ): Page<Reservation>

    fun findAllByMemberIdAndStatus(
        memberId: Long?,
        status: ReservationStatus,
        pageable: Pageable,
    ): Page<Reservation>

    fun countByMemberId(memberId: Long?): Long

    @Query(
        value =
            """
            SELECT r FROM Reservation r
            WHERE r.memberId = :memberId
              AND (:status IS NULL OR r.status = :status)
              AND (:keyword IS NULL OR :keyword = '' OR EXISTS (
                    SELECT 1 FROM Space s
                    WHERE s.id = r.spaceId AND s.name LIKE %:keyword%
              ))
            """,
        countQuery =
            """
            SELECT COUNT(r) FROM Reservation r
            WHERE r.memberId = :memberId
              AND (:status IS NULL OR r.status = :status)
              AND (:keyword IS NULL OR :keyword = '' OR EXISTS (
                    SELECT 1 FROM Space s
                    WHERE s.id = r.spaceId AND s.name LIKE %:keyword%
              ))
            """,
    )
    fun searchMemberReservations(
        @Param("memberId") memberId: Long?,
        @Param("status") status: ReservationStatus?,
        @Param("keyword") keyword: String?,
        pageable: Pageable,
    ): Page<Reservation>

    @Query(
        value =
            """
            SELECT r FROM Reservation r
            WHERE (:spaceId IS NULL OR r.spaceId = :spaceId)
              AND (:status IS NULL OR r.status = :status)
              AND (:startTimeInclusive IS NULL OR r.startTime >= :startTimeInclusive)
              AND (:startTimeExclusive IS NULL OR r.startTime < :startTimeExclusive)
            """,
        countQuery =
            """
            SELECT COUNT(r) FROM Reservation r
            WHERE (:spaceId IS NULL OR r.spaceId = :spaceId)
              AND (:status IS NULL OR r.status = :status)
              AND (:startTimeInclusive IS NULL OR r.startTime >= :startTimeInclusive)
              AND (:startTimeExclusive IS NULL OR r.startTime < :startTimeExclusive)
            """,
    )
    fun searchReservations(
        @Param("spaceId") spaceId: Long?,
        @Param("status") status: ReservationStatus?,
        @Param("startTimeInclusive") startTimeInclusive: LocalDateTime?,
        @Param("startTimeExclusive") startTimeExclusive: LocalDateTime?,
        pageable: Pageable,
    ): Page<Reservation>

    @Query("SELECT r.id FROM Reservation r WHERE r.status = :held AND r.holdExpiresAt <= :now")
    fun findExpiredHoldIds(
        @Param("now") now: LocalDateTime,
        @Param("held") held: ReservationStatus,
    ): List<Long>

    @Query("SELECT r.id FROM Reservation r WHERE r.status = :confirmed AND r.startTime < :startTimeBefore")
    fun findNoShowCandidateIds(
        @Param("startTimeBefore") startTimeBefore: LocalDateTime,
        @Param("confirmed") confirmed: ReservationStatus,
    ): List<Long>

    @Query("SELECT r.id FROM Reservation r WHERE r.status = :inUse AND r.endTime <= :now")
    fun findAutoCheckOutCandidateIds(
        @Param("now") now: LocalDateTime,
        @Param("inUse") inUse: ReservationStatus,
    ): List<Long>

    /** 체크인 마감이 지났는데 아직 CONFIRMED일 때만 NO_SHOW로 전이한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Reservation r SET r.status = :noShow " +
            "WHERE r.id = :id AND r.status = :confirmed AND r.startTime < :startTimeBefore",
    )
    fun markNoShowIfNotCheckedIn(
        @Param("id") id: Long?,
        @Param("startTimeBefore") startTimeBefore: LocalDateTime,
        @Param("confirmed") confirmed: ReservationStatus,
        @Param("noShow") noShow: ReservationStatus,
    ): Int

    /** 종료된 IN_USE 예약을 COMPLETED로 전이하고 checked_out_at에는 end_time을 기록한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Reservation r SET r.status = :completed, r.checkedOutAt = r.endTime " +
            "WHERE r.id = :id AND r.status = :inUse AND r.endTime <= :now",
    )
    fun autoCheckOutIfEnded(
        @Param("id") id: Long?,
        @Param("now") now: LocalDateTime,
        @Param("inUse") inUse: ReservationStatus,
        @Param("completed") completed: ReservationStatus,
    ): Int

    @Query("SELECT r.spaceId FROM Reservation r WHERE r.id = :id")
    fun findSpaceIdById(
        @Param("id") id: Long?,
    ): Long?

    @Query(
        "SELECT new com.ovengers.slotkey.reservation.dto.ReservationTargetInfo(r.spaceId, r.memberId) " +
            "FROM Reservation r WHERE r.id = :id",
    )
    fun findTargetInfoById(
        @Param("id") id: Long?,
    ): ReservationTargetInfo?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long?,
    ): Optional<Reservation>

    fun existsByMemberIdAndStatusIn(
        memberId: Long?,
        statuses: List<ReservationStatus>,
    ): Boolean

    @Query(
        "SELECT COUNT(r) > 0 FROM Reservation r " +
            "WHERE r.memberId = :memberId AND r.spaceId = :spaceId " +
            "AND r.startTime < :endTime AND r.endTime > :startTime " +
            "AND (r.status IN (:occupiedStatuses) OR (r.status = :heldStatus AND :now < r.holdExpiresAt))",
    )
    fun existsOccupyingByMemberAndSpace(
        @Param("memberId") memberId: Long?,
        @Param("spaceId") spaceId: Long?,
        @Param("startTime") startTime: LocalDateTime,
        @Param("endTime") endTime: LocalDateTime,
        @Param("now") now: LocalDateTime,
        @Param("heldStatus") heldStatus: ReservationStatus,
        @Param("occupiedStatuses") occupiedStatuses: List<ReservationStatus>,
    ): Boolean
}
