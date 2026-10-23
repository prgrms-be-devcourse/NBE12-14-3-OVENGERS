package com.ovengers.slotkey.reservation.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import java.time.LocalDateTime

@JvmRecord
data class AdminReservationDetailResponse(
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
    val pricePerSlotSnapshot: Int,
    val createdAt: LocalDateTime,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val cancelledAt: LocalDateTime?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val checkedInAt: LocalDateTime?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val checkedOutAt: LocalDateTime?,
    val statusHistory: List<ReservationStatusHistoryResponse>,
    val accessLogs: List<DoorAccessLogResponse>,
) {
    companion object {
        @JvmStatic
        fun from(
            reservation: Reservation,
            memberEmail: String?,
            spaceName: String?,
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
                reservation.cancelledAt,
                reservation.checkedInAt,
                reservation.checkedOutAt,
                statusHistory,
                accessLogs,
            )
    }
}
