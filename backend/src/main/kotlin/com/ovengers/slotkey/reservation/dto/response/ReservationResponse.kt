package com.ovengers.slotkey.reservation.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.ovengers.slotkey.reservation.entity.Reservation
import java.time.LocalDateTime

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
    val holdExpiresAt: LocalDateTime?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val checkedInAt: LocalDateTime?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val checkedOutAt: LocalDateTime?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val cancelledAt: LocalDateTime?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val spaceVersion: Int?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val refundAmount: Int?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val penaltyAmount: Int?,
    val createdAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(reservation: Reservation): ReservationResponse = from(reservation, null)

        @JvmStatic
        fun from(
            reservation: Reservation,
            spaceVersion: Int?,
        ): ReservationResponse = create(reservation, spaceVersion, null, null)

        /** 취소 시 실제 환불액과 위약금만 채운다. */
        @JvmStatic
        fun ofCancelled(
            reservation: Reservation,
            refundAmount: Int,
            penaltyAmount: Int,
        ): ReservationResponse = create(reservation, null, refundAmount, penaltyAmount)

        private fun create(
            reservation: Reservation,
            spaceVersion: Int?,
            refundAmount: Int?,
            penaltyAmount: Int?,
        ): ReservationResponse =
            ReservationResponse(
                reservation.id,
                reservation.spaceId,
                reservation.startTime,
                reservation.endTime,
                reservation.status.name,
                reservation.pricePerSlotSnapshot,
                reservation.totalAmount,
                reservation.holdExpiresAt,
                reservation.checkedInAt,
                reservation.checkedOutAt,
                reservation.cancelledAt,
                spaceVersion,
                refundAmount,
                penaltyAmount,
                reservation.createdAt,
            )
    }
}
