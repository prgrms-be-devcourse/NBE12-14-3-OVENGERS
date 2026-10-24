package com.ovengers.slotkey.space.dto.request

import java.time.LocalDate
import java.time.LocalTime

/**
 * 공간 목록 조회 조건.
 * - keyword, location: 앞뒤 공백 trim 후 검색 적용 (keyword는 이름, 위치, 설명 통합 부분일치 검색).
 * - minPrice, maxPrice: 0 이상 정수, minPrice <= maxPrice 필수.
 * - date/startTime/endTime: 시간 필터 적용 시 3개 필드 동시 필수 (일부만 입력 시 400 Bad Request 거절).
 *   - 30분 단위 정렬 필수 (minute % 30 == 0, second == 0, nano == 0).
 *   - 시간 역전 불가 (startTime < endTime).
 *   - 공간의 영업시간 내 완전 포함 필수 (openingTime <= startTime && closingTime >= endTime).
 *   - 위 조건을 만족하고 해당 시간대에 점유된 슬롯이 하나도 없는 공간만 반환 (가용성 참고용 스냅샷 — 실제 예약 가능 여부는 예약 생성 시점에 다시 검증).
 */
@JvmRecord
data class SpaceSearchCondition(
    val keyword: String?,
    val location: String?,
    val minPrice: Long?,
    val maxPrice: Long?,
    val date: LocalDate?,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
) {
    fun hasTimeFilter(): Boolean = date != null && startTime != null && endTime != null
}
