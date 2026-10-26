package com.ovengers.slotkey.reservation.policy

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 예약 시간 자체에 대한 검사(슬롯 가용성 검사는 여기 포함하지 않는다 — core-domain-decisions 4-2, §9).
 * SlotAlignmentRule / WithinOperatingHoursRule / NotPastRule을 하나로 모은 정적 검증기.
 */
object ReservationTimePolicy {
    private const val SLOT_MINUTES = 30

    /**
     * @param startTime 예약 시작
     * @param endTime 예약 종료
     * @param opening 공간 운영 시작 시각
     * @param closing 공간 운영 종료 시각
     * @param now 현재 시각(Clock 주입)
     */
    @JvmStatic
    fun validate(
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        opening: LocalTime,
        closing: LocalTime,
        now: LocalDateTime,
    ) {
        if (!startTime.isBefore(endTime)) {
            throw BusinessException(ErrorCode.INVALID_RESERVATION_TIME, "종료 시각은 시작 시각보다 늦어야 합니다.")
        }
        if (!startTime.isAfter(now)) {
            throw BusinessException(ErrorCode.INVALID_RESERVATION_TIME, "과거 시각은 예약할 수 없습니다.")
        }
        if (!isSlotAligned(startTime) || !isSlotAligned(endTime)) {
            throw BusinessException(ErrorCode.INVALID_RESERVATION_TIME, "예약 시간은 30분 단위여야 합니다.")
        }
        if (startTime.toLocalDate() != endTime.toLocalDate()) {
            throw BusinessException(ErrorCode.INVALID_RESERVATION_TIME, "예약은 같은 날짜 내에서만 가능합니다.")
        }
        val start = startTime.toLocalTime()
        val end = endTime.toLocalTime()
        val withinHours = !start.isBefore(opening) && !end.isAfter(closing)
        if (!withinHours) {
            throw BusinessException(ErrorCode.INVALID_RESERVATION_TIME, "오피스 운영시간 내에서만 예약할 수 있습니다.")
        }
    }

    @JvmStatic
    fun validateExtension(
        currentEndTime: LocalDateTime,
        newEndTime: LocalDateTime,
        closing: LocalTime,
    ) {
        if (!newEndTime.isAfter(currentEndTime)) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "연장 시각은 기존 종료 시각보다 늦어야 합니다.")
        }
        if (!isSlotAligned(currentEndTime) || !isSlotAligned(newEndTime)) {
            throw BusinessException(ErrorCode.INVALID_RESERVATION_TIME, "예약 시간은 30분 단위여야 합니다.")
        }
        if (currentEndTime.toLocalDate() != newEndTime.toLocalDate()) {
            throw BusinessException(ErrorCode.INVALID_RESERVATION_TIME, "연장은 기존 예약과 같은 날짜 내에서만 가능합니다.")
        }
        val newEnd = newEndTime.toLocalTime()
        if (newEnd.isAfter(closing)) {
            throw BusinessException(ErrorCode.INVALID_RESERVATION_TIME, "오피스 운영시간 내에서만 연장할 수 있습니다.")
        }
    }

    private fun isSlotAligned(time: LocalDateTime): Boolean = time.minute % SLOT_MINUTES == 0 && time.second == 0 && time.nano == 0
}
