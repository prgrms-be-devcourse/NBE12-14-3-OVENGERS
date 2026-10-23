package com.ovengers.slotkey.reservation.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.space.entity.Space
import java.time.LocalDate
import java.time.LocalDateTime

@JvmRecord
data class ReservationListResponse(
    val reservationId: Long,
    val spaceId: Long,
    val spaceName: String,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val spaceLocation: String?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val spaceImagePath: String?,
    val date: LocalDate,
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
) {
    companion object {
        @JvmStatic
        fun from(
            reservation: Reservation,
            space: Space?,
        ): ReservationListResponse =
            ReservationListResponse(
                reservation.id,
                reservation.spaceId,
                space?.name ?: "오피스 정보 없음",
                space?.location,
                space?.imagePath,
                reservation.startTime.toLocalDate(),
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
            )
    }
}
