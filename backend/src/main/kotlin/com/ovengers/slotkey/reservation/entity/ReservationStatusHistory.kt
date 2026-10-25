package com.ovengers.slotkey.reservation.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "reservation_status_history")
class ReservationStatusHistory() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    final var id: Long? = null
        private set

    @get:JvmName("getReservationIdValue")
    @field:Column(name = "reservation_id", nullable = false)
    final var reservationId: Long = 0
        private set

    @field:Column(name = "changed_by_member_id")
    final var changedByMemberId: Long? = null
        private set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "from_status", length = 20)
    final var fromStatus: ReservationStatus? = null
        private set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "to_status", nullable = false, length = 20)
    final lateinit var toStatus: ReservationStatus
        private set

    @field:Column(length = 255)
    final var reason: String? = null
        private set

    @field:Column(name = "changed_at", nullable = false)
    final lateinit var changedAt: LocalDateTime
        private set

    constructor(
        id: Long?,
        reservationId: Long?,
        changedByMemberId: Long?,
        fromStatus: ReservationStatus?,
        toStatus: ReservationStatus,
        reason: String?,
        changedAt: LocalDateTime,
    ) : this() {
        this.id = id
        this.reservationId = requireNotNull(reservationId)
        this.changedByMemberId = changedByMemberId
        this.fromStatus = fromStatus
        this.toStatus = toStatus
        this.reason = reason
        this.changedAt = changedAt
    }

    fun getReservationId(): Long? = reservationId

    class ReservationStatusHistoryBuilder internal constructor() {
        private var id: Long? = null
        private var reservationId: Long? = null
        private var changedByMemberId: Long? = null
        private var fromStatus: ReservationStatus? = null
        private var toStatus: ReservationStatus? = null
        private var reason: String? = null
        private var changedAt: LocalDateTime? = null

        fun id(id: Long?) = apply { this.id = id }

        fun reservationId(reservationId: Long?) = apply { this.reservationId = reservationId }

        fun changedByMemberId(changedByMemberId: Long?) = apply { this.changedByMemberId = changedByMemberId }

        fun fromStatus(fromStatus: ReservationStatus?) = apply { this.fromStatus = fromStatus }

        fun toStatus(toStatus: ReservationStatus) = apply { this.toStatus = toStatus }

        fun reason(reason: String?) = apply { this.reason = reason }

        fun changedAt(changedAt: LocalDateTime) = apply { this.changedAt = changedAt }

        fun build(): ReservationStatusHistory =
            ReservationStatusHistory(
                id,
                reservationId,
                changedByMemberId,
                fromStatus,
                requireNotNull(toStatus),
                reason,
                requireNotNull(changedAt),
            )

        override fun toString(): String =
            "ReservationStatusHistory.ReservationStatusHistoryBuilder(id=$id, " +
                "reservationId=$reservationId, changedByMemberId=$changedByMemberId, " +
                "fromStatus=$fromStatus, toStatus=$toStatus, reason=$reason, changedAt=$changedAt)"
    }

    companion object {
        @JvmStatic
        fun of(
            reservationId: Long?,
            changedByMemberId: Long?,
            fromStatus: ReservationStatus?,
            toStatus: ReservationStatus,
            reason: String?,
            changedAt: LocalDateTime,
        ): ReservationStatusHistory =
            ReservationStatusHistory(
                null,
                reservationId,
                changedByMemberId,
                fromStatus,
                toStatus,
                reason,
                changedAt,
            )

        @JvmStatic
        fun builder(): ReservationStatusHistoryBuilder = ReservationStatusHistoryBuilder()
    }
}
