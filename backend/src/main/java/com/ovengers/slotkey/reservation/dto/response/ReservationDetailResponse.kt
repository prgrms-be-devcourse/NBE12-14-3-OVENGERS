package com.ovengers.slotkey.reservation.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import java.time.LocalDateTime

@JvmRecord
data class ReservationDetailResponse(
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
    val createdAt: LocalDateTime,
    val statusHistory: List<ReservationStatusHistoryResponse>,
) {
    companion object {
        @JvmStatic
        fun of(
            reservation: Reservation,
            histories: List<ReservationStatusHistory>,
        ): ReservationDetailResponse =
            ReservationDetailResponse(
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
                reservation.createdAt,
                histories.map { ReservationStatusHistoryResponse.from(it) },
            )
    }
}
