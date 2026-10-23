package com.ovengers.slotkey.space.service;

import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.space.dto.request.SpaceSearchCondition;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class SpaceQueryService {

    /**
     * 어떤 공간 id와도 매치되지 않는 sentinel 값. "AND s.id NOT IN :excludedSpaceIds" 조건을
     * 항상 동일한 쿼리로 유지하기 위해, 제외할 공간이 없을 때도 빈 컬렉션 대신 이 값을 바인딩한다.
     */
    private static final List<Long> NO_EXCLUSION = List.of(-1L);

    private final SpaceRepository spaceRepository;
    private final OccupiedSlotProvider occupiedSlotProvider;
    private final Clock clock;

    /**
     * 오피스 찾기 목록 조회. keyword/location/가격 범위는 단순 WHERE 조건이고,
     * date+startTime+endTime을 모두 지정하면 영업시간 필터 및 점유 슬롯 제외를 적용한다.
     */
    public Page<Space> getSpacesPage(Pageable pageable, SpaceSearchCondition condition) {
        String keyword = normalize(condition.keyword());
        String location = normalize(condition.location());

        validatePrice(condition.minPrice(), condition.maxPrice());
        validateTimeFilter(condition);

        List<Long> excludedSpaceIds = NO_EXCLUSION;
        LocalTime queryStartTime = null;
        LocalTime queryEndTime = null;

        if (condition.hasTimeFilter()) {
            queryStartTime = condition.startTime();
            queryEndTime = condition.endTime();

            LocalDateTime start = LocalDateTime.of(condition.date(), queryStartTime);
            LocalDateTime end = LocalDateTime.of(condition.date(), queryEndTime);

            Set<Long> occupiedSpaceIds = occupiedSlotProvider.getOccupiedSpaceIds(
                    start, end, LocalDateTime.now(clock));
            if (!occupiedSpaceIds.isEmpty()) {
                excludedSpaceIds = List.copyOf(occupiedSpaceIds);
            }
        }

        return spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                keyword,
                location,
                condition.minPrice(),
                condition.maxPrice(),
                queryStartTime,
                queryEndTime,
                excludedSpaceIds,
                pageable);
    }

    private void validatePrice(Long minPrice, Long maxPrice) {
        if (minPrice != null && minPrice < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (maxPrice != null && maxPrice < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateTimeFilter(SpaceSearchCondition condition) {
        boolean hasAnyTimeField = condition.date() != null || condition.startTime() != null || condition.endTime() != null;
        if (hasAnyTimeField && !condition.hasTimeFilter()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        if (condition.hasTimeFilter()) {
            LocalTime start = condition.startTime();
            LocalTime end = condition.endTime();

            if (isNotHalfHourAligned(start) || isNotHalfHourAligned(end)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }

            if (!start.isBefore(end)) {
                throw new BusinessException(ErrorCode.INVALID_TIME_RANGE);
            }
        }
    }

    private boolean isNotHalfHourAligned(LocalTime time) {
        return time.getMinute() % 30 != 0 || time.getSecond() != 0 || time.getNano() != 0;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public Space getSpaceDetailById(Long spaceId) {
        return spaceRepository.findById(spaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));
    }

    public SpaceQueryService(
            final SpaceRepository spaceRepository,
            final OccupiedSlotProvider occupiedSlotProvider,
            final Clock clock) {
        this.spaceRepository = spaceRepository;
        this.occupiedSlotProvider = occupiedSlotProvider;
        this.clock = clock;
    }
}
