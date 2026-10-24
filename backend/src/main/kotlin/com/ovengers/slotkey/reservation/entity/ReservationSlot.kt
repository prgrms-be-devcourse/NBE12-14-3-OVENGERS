package com.ovengers.slotkey.reservation.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 30분 단위 슬롯 점유 행. 살아있는 점유일 때만 존재하며,
 * UNIQUE(space_id, slot_start)가 동시 예약 차단의 유일한 진실이다(core-domain-decisions 4-1, core-domain-decisions 4-2).
 * 취소/노쇼/만료 시 하드 삭제한다.
 */
@Entity
@Table(name = "reservation_slot")
class ReservationSlot {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @get:JvmName("getIdValue")
    final var id: Long = 0
        protected set

    @get:JvmName("getReservationIdValue")
    @field:Column(name = "reservation_id", nullable = false)
    final var reservationId: Long = 0
        protected set

    @get:JvmName("getSpaceIdValue")
    @field:Column(name = "space_id", nullable = false)
    final var spaceId: Long = 0
        protected set

    @field:Column(name = "slot_start", nullable = false)
    final lateinit var slotStart: LocalDateTime
        protected set

    constructor()

    constructor(
        id: Long?,
        reservationId: Long?,
        spaceId: Long?,
        slotStart: LocalDateTime?,
    ) {
        id?.let { this.id = it }
        reservationId?.let { this.reservationId = it }
        spaceId?.let { this.spaceId = it }
        slotStart?.let { this.slotStart = it }
    }

    fun getId(): Long? = id

    fun getReservationId(): Long? = reservationId

    fun getSpaceId(): Long? = spaceId

    /** Java 호출부와 테스트가 사용하는 기존 빌더 API를 유지한다. */
    class ReservationSlotBuilder {
        private var id: Long? = null
        private var reservationId: Long? = null
        private var spaceId: Long? = null
        private var slotStart: LocalDateTime? = null

        fun id(id: Long?) = apply { this.id = id }

        fun reservationId(reservationId: Long?) = apply { this.reservationId = reservationId }

        fun spaceId(spaceId: Long?) = apply { this.spaceId = spaceId }

        fun slotStart(slotStart: LocalDateTime?) = apply { this.slotStart = slotStart }

        fun build() = ReservationSlot(id, reservationId, spaceId, slotStart)

        override fun toString(): String =
            "ReservationSlot.ReservationSlotBuilder(id=$id, reservationId=$reservationId, " +
                "spaceId=$spaceId, slotStart=$slotStart)"
    }

    companion object {
        @JvmStatic
        fun of(
            reservationId: Long?,
            spaceId: Long?,
            slotStart: LocalDateTime,
        ) = ReservationSlot(null, reservationId, spaceId, slotStart)

        @JvmStatic
        fun builder() = ReservationSlotBuilder()
    }
}
