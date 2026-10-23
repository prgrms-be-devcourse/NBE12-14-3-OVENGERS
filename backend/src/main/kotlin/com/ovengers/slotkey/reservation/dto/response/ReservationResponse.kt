package com.ovengers.slotkey.reservation.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.ovengers.slotkey.reservation.entity.Reservation
import java.time.LocalDateTime
import java.util.Optional

@JvmRecord
data class ReservationResponse(
    val reservationId: Long,
    val spaceId: Long,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: String,
    val pricePerSlotSnapshot: Int,
    val totalAmount: Int,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val holdExpiresAt: Optional<LocalDateTime>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val checkedInAt: Optional<LocalDateTime>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val checkedOutAt: Optional<LocalDateTime>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val cancelledAt: Optional<LocalDateTime>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val spaceVersion: Optional<Int>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val refundAmount: Optional<Int>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val penaltyAmount: Optional<Int>,
    val createdAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(reservation: Reservation): ReservationResponse = from(reservation, Optional.empty())

        @JvmStatic
        fun from(
            reservation: Reservation,
            spaceVersion: Optional<Int>,
        ): ReservationResponse = create(reservation, spaceVersion, Optional.empty(), Optional.empty())

        /** 취소 시 실제 환불액과 위약금만 채운다. */
        @JvmStatic
        fun ofCancelled(
            reservation: Reservation,
            refundAmount: Int,
            penaltyAmount: Int,
        ): ReservationResponse = create(reservation, Optional.empty(), Optional.of(refundAmount), Optional.of(penaltyAmount))

        private fun create(
            reservation: Reservation,
            spaceVersion: Optional<Int>,
            refundAmount: Optional<Int>,
            penaltyAmount: Optional<Int>,
        ): ReservationResponse =
            ReservationResponse(
                reservation.id,
                reservation.spaceId,
                reservation.startTime,
                reservation.endTime,
                reservation.status.name,
                reservation.pricePerSlotSnapshot,
                reservation.totalAmount,
                Optional.ofNullable(reservation.holdExpiresAt),
                Optional.ofNullable(reservation.checkedInAt),
                Optional.ofNullable(reservation.checkedOutAt),
                Optional.ofNullable(reservation.cancelledAt),
                spaceVersion,
                refundAmount,
                penaltyAmount,
                reservation.createdAt,
            )
    }
}
