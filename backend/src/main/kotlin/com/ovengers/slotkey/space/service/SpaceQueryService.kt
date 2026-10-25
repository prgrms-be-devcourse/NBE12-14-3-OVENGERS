package com.ovengers.slotkey.space.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.dto.request.SpaceSearchCondition
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime

@Service
@Transactional(readOnly = true)
class SpaceQueryService(
    private val spaceRepository: SpaceRepository,
    private val occupiedSlotProvider: OccupiedSlotProvider,
    private val clock: Clock,
) {
    companion object {
        /**
         * 어떤 공간 id와도 매치되지 않는 sentinel 값. "AND s.id NOT IN :excludedSpaceIds" 조건을
         * 항상 동일한 쿼리로 유지하기 위해, 제외할 공간이 없을 때도 빈 컬렉션 대신 이 값을 바인딩한다.
         */
        private val NO_EXCLUSION: List<Long> = listOf(-1L)
    }

    /**
     * 오피스 찾기 목록 조회. keyword/location/가격 범위는 단순 WHERE 조건이고,
     * date+startTime+endTime을 모두 지정하면 영업시간 필터 및 점유 슬롯 제외를 적용한다.
     */
    fun getSpacesPage(
        pageable: Pageable?,
        condition: SpaceSearchCondition?,
    ): Page<Space> {
        val cond = condition!!
        val keyword = normalize(cond.keyword)
        val location = normalize(cond.location)

        validatePrice(cond.minPrice, cond.maxPrice)
        validateTimeFilter(cond)

        var excludedSpaceIds: List<Long> = NO_EXCLUSION
        var queryStartTime: LocalTime? = null
        var queryEndTime: LocalTime? = null

        if (cond.hasTimeFilter()) {
            queryStartTime = cond.startTime
            queryEndTime = cond.endTime

            val start = LocalDateTime.of(cond.date, queryStartTime)
            val end = LocalDateTime.of(cond.date, queryEndTime)

            val occupiedSpaceIds =
                occupiedSlotProvider.getOccupiedSpaceIds(
                    start,
                    end,
                    LocalDateTime.now(clock),
                )
            if (occupiedSpaceIds.isNotEmpty()) {
                excludedSpaceIds = java.util.List.copyOf(occupiedSpaceIds)
            }
        }

        return spaceRepository.searchSpaces(
            SpaceStatus.ACTIVE,
            keyword,
            location,
            cond.minPrice,
            cond.maxPrice,
            queryStartTime,
            queryEndTime,
            excludedSpaceIds,
            pageable,
        )
    }

    private fun validatePrice(
        minPrice: Long?,
        maxPrice: Long?,
    ) {
        if (minPrice != null && minPrice < 0) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED)
        }
        if (maxPrice != null && maxPrice < 0) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED)
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED)
        }
    }

    private fun validateTimeFilter(condition: SpaceSearchCondition) {
        val hasAnyTimeField = condition.date != null || condition.startTime != null || condition.endTime != null
        if (hasAnyTimeField && !condition.hasTimeFilter()) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED)
        }

        if (condition.hasTimeFilter()) {
            val start = condition.startTime
            val end = condition.endTime

            if (start == null || end == null || isNotHalfHourAligned(start) || isNotHalfHourAligned(end)) {
                throw BusinessException(ErrorCode.VALIDATION_FAILED)
            }

            if (!start.isBefore(end)) {
                throw BusinessException(ErrorCode.INVALID_TIME_RANGE)
            }
        }
    }

    private fun isNotHalfHourAligned(time: LocalTime): Boolean = time.minute % 30 != 0 || time.second != 0 || time.nano != 0

    private fun normalize(value: String?): String? {
        if (value == null) {
            return null
        }
        val trimmed = value.trim { it <= ' ' }
        return if (trimmed.isEmpty()) null else trimmed
    }

    fun getSpaceDetailById(spaceId: Long?): Space =
        spaceRepository
            .findById(spaceId)
            .orElseThrow { BusinessException(ErrorCode.SPACE_NOT_FOUND) }
}
