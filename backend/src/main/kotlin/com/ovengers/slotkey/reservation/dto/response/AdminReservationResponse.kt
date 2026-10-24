package com.ovengers.slotkey.reservation.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import java.time.LocalDateTime

@JvmRecord
data class AdminReservationResponse(
    val reservationId: Long,
    val memberId: Long,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val memberEmail: String?,
    val spaceId: Long,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val spaceName: String?,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: ReservationStatus,
    val totalAmount: Int,
    val createdAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(
            reservation: Reservation,
            memberEmail: String?,
            spaceName: String?,
        ): AdminReservationResponse =
            AdminReservationResponse(
                reservation.id,
                reservation.memberId,
                memberEmail,
                reservation.spaceId,
                spaceName,
                reservation.startTime,
                reservation.endTime,
                reservation.status,
                reservation.totalAmount,
                reservation.createdAt,
            )
    }
}
