package com.ovengers.slotkey.space.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.dto.response.SlotResponse
import com.ovengers.slotkey.space.dto.response.SpaceSlotAvailabilityResponse
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.policy.SpaceOperatingHoursPolicy
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class SpaceSlotAvailabilityService(
    private val spaceRepository: SpaceRepository,
    private val operatingHoursPolicy: SpaceOperatingHoursPolicy,
    private val occupiedSlotProvider: OccupiedSlotProvider,
    private val clock: Clock,
) {
    /**
     * 특정 날짜의 30분 단위 슬롯 가용성 스냅샷을 조회합니다.
     */
    fun getSlotAvailability(
        spaceId: Long?,
        date: LocalDate?,
    ): SpaceSlotAvailabilityResponse {
        val space: Space =
            spaceRepository
                .findById(spaceId)
                .orElseThrow { BusinessException(ErrorCode.SPACE_NOT_FOUND) }

        val slotWindows =
            operatingHoursPolicy.generateSlots(
                date,
                space.openingTime,
                space.closingTime,
            )

        val now = LocalDateTime.now(clock)
        val occupiedSlots = occupiedSlotProvider.getOccupiedSlotStarts(spaceId, date, now)
        val isSpaceActive = (space.status == SpaceStatus.ACTIVE)

        val slotResponses =
            slotWindows
                .stream()
                .map { window ->
                    // 1. 공간 자체가 비활성(INACTIVE)이면 예약 불가
                    // 2. 과거 시각이면 예약 불가
                    // 3. 이미 다른 예약에 의해 점유되었으면 예약 불가
                    val isPast = !window.start.isAfter(now)
                    val isOccupied = occupiedSlots.contains(window.start)
                    val available = isSpaceActive && !isPast && !isOccupied

                    SlotResponse.of(window.start, window.end, available)
                }.toList()

        return SpaceSlotAvailabilityResponse.of(spaceId!!, date!!, slotResponses)
    }
}
