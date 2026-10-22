package com.ovengers.slotkey.reservation.dto

/**
 * 예약 대상 리소스 식별 정보 프로젝션 (잠금 순서 준수 및 사전 권한 검증용).
 */
@JvmRecord
data class ReservationTargetInfo(
    val spaceId: Long,
    val memberId: Long,
)
