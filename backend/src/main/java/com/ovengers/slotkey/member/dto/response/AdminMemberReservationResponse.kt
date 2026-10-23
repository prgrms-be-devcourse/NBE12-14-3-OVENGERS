package com.ovengers.slotkey.member.dto.response

import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import java.time.LocalDateTime

@JvmRecord
data class AdminMemberReservationResponse(
    val reservationId: Long,
    val spaceId: Long,
    val spaceName: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: ReservationStatus,
    val totalAmount: Int?,
    val createdAt: LocalDateTime,
) {
    companion object {
        @JvmStatic
        fun from(
            reservation: Reservation,
            spaceName: String,
        ) = AdminMemberReservationResponse(
            reservation.getId(),
            reservation.getSpaceId(),
            spaceName,
            reservation.getStartTime(),
            reservation.getEndTime(),
            reservation.getStatus(),
            reservation.getTotalAmount(),
            reservation.getCreatedAt(),
        )
    }
}
