package com.ovengers.slotkey.reservation.dto.request

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "본인 예약 목록 검색 조건")
@JvmRecord
data class ReservationSearchCondition(
    @field:Schema(description = "예약 상태 필터 (미지정 시 전체)", example = "CONFIRMED")
    @get:Schema(description = "예약 상태 필터 (미지정 시 전체)", example = "CONFIRMED")
    val status: ReservationStatus?,
    @field:Schema(description = "공간 이름 검색어 (부분 일치, 앞뒤 공백 trim 및 공백만 입력 시 전체)", example = "회의실")
    @get:Schema(description = "공간 이름 검색어 (부분 일치, 앞뒤 공백 trim 및 공백만 입력 시 전체)", example = "회의실")
    val keyword: String?,
)
