package com.ovengers.slotkey.reservation.repository

import com.ovengers.slotkey.reservation.entity.ReservationSlot
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
@JvmSuppressWildcards
interface ReservationSlotRepository : JpaRepository<ReservationSlot, Long> {
    /** 특정 공간·날짜의 점유 슬롯 조회. */
    fun findAllBySpaceIdAndSlotStartBetween(
        spaceId: Long?,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<ReservationSlot>

    /** 반개구간 내 실제 유효 점유 상태인 슬롯의 시작 시각 목록을 조회한다. */
    @Query(
        "SELECT rs.slotStart FROM ReservationSlot rs, Reservation r " +
            "WHERE rs.reservationId = r.id " +
            "AND rs.spaceId = :spaceId " +
            "AND rs.slotStart >= :startInclusive AND rs.slotStart < :endExclusive " +
            "AND (r.status IN (:confirmedStatuses) OR (r.status = :heldStatus AND :now < r.holdExpiresAt))",
    )
    fun findOccupiedSlotStarts(
        @Param("spaceId") spaceId: Long?,
        @Param("startInclusive") startInclusive: LocalDateTime,
        @Param("endExclusive") endExclusive: LocalDateTime,
        @Param("now") now: LocalDateTime,
        @Param("heldStatus") heldStatus: ReservationStatus,
        @Param("confirmedStatuses") confirmedStatuses: List<ReservationStatus>,
    ): List<LocalDateTime>

    /** 진행 중인 슬롯을 포함하여 fromTime 이후에 끝나는 모든 유효 점유 슬롯을 조회한다. */
    @Query(
        "SELECT rs.slotStart FROM ReservationSlot rs, Reservation r " +
            "WHERE rs.reservationId = r.id " +
            "AND rs.spaceId = :spaceId " +
            "AND rs.slotStart > :slotStartAfter " +
            "AND (r.status IN (:confirmedStatuses) OR (r.status = :heldStatus AND :fromTime < r.holdExpiresAt))",
    )
    fun findOccupiedSlotStartsEndingAfter(
        @Param("spaceId") spaceId: Long?,
        @Param("slotStartAfter") slotStartAfter: LocalDateTime,
        @Param("fromTime") fromTime: LocalDateTime,
        @Param("heldStatus") heldStatus: ReservationStatus,
        @Param("confirmedStatuses") confirmedStatuses: List<ReservationStatus>,
    ): List<LocalDateTime>

    /** 요청 슬롯 중 만료 정리 대상인 예약 id 목록을 조회한다. */
    @Query(
        "SELECT DISTINCT rs.reservationId FROM ReservationSlot rs, Reservation r " +
            "WHERE rs.reservationId = r.id " +
            "AND rs.spaceId = :spaceId AND rs.slotStart IN :slotStarts " +
            "AND ((r.status = :heldStatus AND r.holdExpiresAt <= :now) OR r.status = :expiredStatus)",
    )
    fun findExpiredReservationIdsBySpaceIdAndSlotStartIn(
        @Param("spaceId") spaceId: Long?,
        @Param("slotStarts") slotStarts: List<LocalDateTime>,
        @Param("now") now: LocalDateTime,
        @Param("heldStatus") heldStatus: ReservationStatus,
        @Param("expiredStatus") expiredStatus: ReservationStatus,
    ): List<Long>

    /** 요청한 슬롯 시작 시각들 중 이미 점유된 슬롯을 가진 예약 id 목록을 조회한다. */
    @Query(
        "SELECT DISTINCT rs.reservationId FROM ReservationSlot rs " +
            "WHERE rs.spaceId = :spaceId AND rs.slotStart IN :slotStarts",
    )
    fun findReservationIdsBySpaceIdAndSlotStartIn(
        @Param("spaceId") spaceId: Long?,
        @Param("slotStarts") slotStarts: List<LocalDateTime>,
    ): List<Long>

    /** 실제 EXPIRED 상태인 예약의 슬롯만 삭제한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "DELETE FROM ReservationSlot rs WHERE rs.reservationId IN " +
            "(SELECT r.id FROM Reservation r WHERE r.id IN :ids AND r.status = :expired)",
    )
    fun deleteSlotsOfExpiredReservations(
        @Param("ids") ids: List<Long>,
        @Param("expired") expired: ReservationStatus,
    ): Int

    /** 지정한 반개구간 안에서 유효 점유 슬롯을 가진 공간 id 목록을 조회한다. */
    @Query(
        "SELECT DISTINCT rs.spaceId FROM ReservationSlot rs, Reservation r " +
            "WHERE rs.reservationId = r.id " +
            "AND rs.slotStart >= :startInclusive AND rs.slotStart < :endExclusive " +
            "AND (r.status IN (:confirmedStatuses) OR (r.status = :heldStatus AND :now < r.holdExpiresAt))",
    )
    fun findOccupiedSpaceIds(
        @Param("startInclusive") startInclusive: LocalDateTime,
        @Param("endExclusive") endExclusive: LocalDateTime,
        @Param("now") now: LocalDateTime,
        @Param("heldStatus") heldStatus: ReservationStatus,
        @Param("confirmedStatuses") confirmedStatuses: List<ReservationStatus>,
    ): List<Long>

    fun findAllByReservationId(reservationId: Long?): List<ReservationSlot>

    fun deleteByReservationId(reservationId: Long?): Long
}
