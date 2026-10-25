package com.ovengers.slotkey.space.service

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 공간 및 날짜에 대해 이미 점유된 슬롯의 시작 시각 목록을 제공하는 인터페이스.
 * 예약 도메인과의 느슨한 결합을 위한 포트 역할을 합니다.
 */
interface OccupiedSlotProvider {
    fun getOccupiedSlotStarts(
        spaceId: Long?,
        date: LocalDate?,
        now: LocalDateTime?,
    ): Set<LocalDateTime>

    /**
     * 특정 시각(fromTime) 이후 유효 점유(CONFIRMED, IN_USE, COMPLETED 및 유효한 HELD)된 슬롯 중,
     * 후보 운영시간(openTime ~ closeTime) 범위 밖(slotStart < openTime 이거나 slotEnd > closeTime)에
     * 걸치는 슬롯이 존재하는지 확인한다.
     */
    fun hasOccupiedSlotsOutsideHours(
        spaceId: Long?,
        openTime: LocalTime?,
        closeTime: LocalTime?,
        fromTime: LocalDateTime?,
    ): Boolean

    /**
     * 지정한 시간 범위 [startInclusive, endExclusive) 안에 유효 점유 슬롯이 하나라도 있는 공간들의
     * id 집합을 반환한다. 공간 목록(오피스 찾기) 조회에서 "이 시간대에 예약 가능한 공간만" 필터링할 때 쓴다.
     */
    fun getOccupiedSpaceIds(
        startInclusive: LocalDateTime?,
        endExclusive: LocalDateTime?,
        now: LocalDateTime?,
    ): Set<Long>
}
