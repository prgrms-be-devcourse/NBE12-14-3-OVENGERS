package com.ovengers.slotkey.reservation.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import java.time.LocalDateTime
import java.util.Optional

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
    val holdExpiresAt: Optional<LocalDateTime>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val checkedInAt: Optional<LocalDateTime>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val checkedOutAt: Optional<LocalDateTime>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val cancelledAt: Optional<LocalDateTime>,
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
                Optional.ofNullable(reservation.holdExpiresAt),
                Optional.ofNullable(reservation.checkedInAt),
                Optional.ofNullable(reservation.checkedOutAt),
                Optional.ofNullable(reservation.cancelledAt),
                reservation.createdAt,
                histories.map { ReservationStatusHistoryResponse.from(it) },
            )
    }
}
