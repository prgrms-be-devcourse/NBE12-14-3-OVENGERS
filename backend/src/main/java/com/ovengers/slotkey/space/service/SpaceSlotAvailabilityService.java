package com.ovengers.slotkey.space.service;

import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.space.dto.response.SlotResponse;
import com.ovengers.slotkey.space.dto.response.SpaceSlotAvailabilityResponse;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import com.ovengers.slotkey.space.policy.SpaceOperatingHoursPolicy;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class SpaceSlotAvailabilityService {

    private final SpaceRepository spaceRepository;
    private final SpaceOperatingHoursPolicy operatingHoursPolicy;
    private final OccupiedSlotProvider occupiedSlotProvider;
    private final Clock clock;

    /**
     * 특정 날짜의 30분 단위 슬롯 가용성 스냅샷을 조회합니다.
     */
    public SpaceSlotAvailabilityResponse getSlotAvailability(Long spaceId, LocalDate date) {
        Space space = spaceRepository.findById(spaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));

        List<SpaceOperatingHoursPolicy.SlotWindow> slotWindows = operatingHoursPolicy.generateSlots(date,
                space.getOpeningTime(), space.getClosingTime());

        LocalDateTime now = LocalDateTime.now(clock);
        Set<LocalDateTime> occupiedSlots = occupiedSlotProvider.getOccupiedSlotStarts(spaceId, date, now);
        boolean isSpaceActive = (space.getStatus() == SpaceStatus.ACTIVE);

        List<SlotResponse> slotResponses = slotWindows.stream()
                .map(window -> {
                    // 1. 공간 자체가 비활성(INACTIVE)이면 예약 불가
                    // 2. 과거 시각이면 예약 불가
                    // 3. 이미 다른 예약에 의해 점유되었으면 예약 불가
                    boolean isPast = !window.start().isAfter(now);
                    boolean isOccupied = occupiedSlots.contains(window.start());
                    boolean available = isSpaceActive && !isPast && !isOccupied;

                    return SlotResponse.of(window.start(), window.end(), available);
                })
                .toList();

        return SpaceSlotAvailabilityResponse.of(spaceId, date, slotResponses);
    }

    public SpaceSlotAvailabilityService(
            final SpaceRepository spaceRepository,
            final SpaceOperatingHoursPolicy operatingHoursPolicy,
            final OccupiedSlotProvider occupiedSlotProvider,
            final Clock clock) {
        this.spaceRepository = spaceRepository;
        this.operatingHoursPolicy = operatingHoursPolicy;
        this.occupiedSlotProvider = occupiedSlotProvider;
        this.clock = clock;
    }
}
