package com.ovengers.slotkey.reservation.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import java.time.LocalDateTime
import java.util.Optional

@JvmRecord
data class AdminReservationDetailResponse(
    val reservationId: Long,
    val memberId: Long,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val memberEmail: Optional<String>,
    val spaceId: Long,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val spaceName: Optional<String>,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: ReservationStatus,
    val totalAmount: Int,
    val pricePerSlotSnapshot: Int,
    val createdAt: LocalDateTime,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val cancelledAt: Optional<LocalDateTime>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val checkedInAt: Optional<LocalDateTime>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val checkedOutAt: Optional<LocalDateTime>,
    val statusHistory: List<ReservationStatusHistoryResponse>,
    val accessLogs: List<DoorAccessLogResponse>,
) {
    companion object {
        @JvmStatic
        fun from(
            reservation: Reservation,
            memberEmail: Optional<String>,
            spaceName: Optional<String>,
            statusHistory: List<ReservationStatusHistoryResponse>,
            accessLogs: List<DoorAccessLogResponse>,
        ): AdminReservationDetailResponse =
            AdminReservationDetailResponse(
                reservation.id,
                reservation.memberId,
                memberEmail,
                reservation.spaceId,
                spaceName,
                reservation.startTime,
                reservation.endTime,
                reservation.status,
                reservation.totalAmount,
                reservation.pricePerSlotSnapshot,
                reservation.createdAt,
                Optional.ofNullable(reservation.cancelledAt),
                Optional.ofNullable(reservation.checkedInAt),
                Optional.ofNullable(reservation.checkedOutAt),
                statusHistory,
                accessLogs,
            )
    }
}
