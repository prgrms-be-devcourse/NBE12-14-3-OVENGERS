package com.ovengers.slotkey.reservation.dto.request

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate

@Schema(description = "관리자 예약 검색 조건. 모든 조건은 선택 사항입니다.")
@JvmRecord
data class AdminReservationSearchCondition(
    @field:Schema(description = "이용 시작 날짜 (YYYY-MM-DD)", example = "2026-09-30")
    @get:Schema(description = "이용 시작 날짜 (YYYY-MM-DD)", example = "2026-09-30")
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val date: LocalDate?,
    @field:Schema(description = "공간 ID (양수)", example = "1")
    @get:Schema(description = "공간 ID (양수)", example = "1")
    @field:Positive(message = "오피스 ID는 양수여야 합니다.")
    val spaceId: Long?,
    @field:Schema(description = "예약 상태")
    @get:Schema(description = "예약 상태")
    val status: ReservationStatus?,
)
