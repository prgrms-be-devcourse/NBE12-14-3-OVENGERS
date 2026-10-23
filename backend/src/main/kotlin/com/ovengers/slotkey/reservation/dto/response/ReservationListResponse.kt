package com.ovengers.slotkey.reservation.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.space.entity.Space
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

@JvmRecord
data class ReservationListResponse(
    val reservationId: Long,
    val spaceId: Long,
    val spaceName: String,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val spaceLocation: Optional<String>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val spaceImagePath: Optional<String>,
    val date: LocalDate,
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
) {
    companion object {
        @JvmStatic
        fun from(
            reservation: Reservation,
            space: Optional<Space>,
        ): ReservationListResponse =
            ReservationListResponse(
                reservation.id,
                reservation.spaceId,
                space.map { it.name }.orElse("오피스 정보 없음"),
                space.map { it.location },
                space.map { it.imagePath },
                reservation.startTime.toLocalDate(),
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
            )
    }
}
