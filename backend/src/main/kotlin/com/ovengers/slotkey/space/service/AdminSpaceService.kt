package com.ovengers.slotkey.space.service

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.service.AuditLogService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.dto.request.SpaceCreateRequest
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse
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
class AdminSpaceService(
    private val spaceRepository: SpaceRepository?,
    private val auditLogService: AuditLogService?,
    private val occupiedSlotProvider: OccupiedSlotProvider?,
    private val clock: Clock?,
) {
    @Transactional(readOnly = true)
    fun getSpaces(
        status: SpaceStatus?,
        keyword: String?,
        pageable: Pageable?,
    ): Page<SpaceDetailResponse> {
        val normalizedKeyword = normalize(keyword)
        return spaceRepository!!
            .searchSpacesByNameOrDescription(normalizedKeyword, status, pageable)
            .map(SpaceDetailResponse::from)
    }

    private fun normalize(value: String?): String? {
        if (value == null) {
            return null
        }
        val trimmed = value.trim { it <= ' ' }
        return if (trimmed.isEmpty()) null else trimmed
    }

    @Transactional(readOnly = true)
    fun getSpaceDetailById(spaceId: Long?): Space =
        spaceRepository!!
            .findById(spaceId)
            .orElseThrow { BusinessException(ErrorCode.SPACE_NOT_FOUND) }

    @Transactional
    fun createSpace(
        request: SpaceCreateRequest?,
        adminMemberId: Long?,
    ): SpaceDetailResponse {
        val space = request!!.toEntity()
        val savedSpace = spaceRepository!!.save(space)

        val response = SpaceDetailResponse.from(savedSpace)

        // 감사 로그 기록
        auditLogService!!.log(
            adminMemberId,
            AuditAction.REGISTER_SPACE,
            AuditTargetType.SPACE,
            savedSpace.id,
            null,
            response,
        )

        return response
    }

    @Transactional
    fun updateSpace(
        spaceId: Long?,
        request: SpaceUpdateRequest?,
        adminMemberId: Long?,
    ): SpaceDetailResponse {
        val space =
            spaceRepository!!
                .findByIdForUpdate(spaceId)
                .orElseThrow { BusinessException(ErrorCode.SPACE_NOT_FOUND) }

        // 1. 후보 운영시간 계산 및 유효성(순서 및 30분 단위) 사전 검증 (실패 시 400 INVALID_OPERATING_HOURS)
        val candidateOpening: LocalTime? = request!!.openingTime ?: space.openingTime
        val candidateClosing: LocalTime? = request.closingTime ?: space.closingTime
        if (request.openingTime != null || request.closingTime != null) {
            Space.validateOperatingHours(candidateOpening, candidateClosing)
        }

        // 2. 운영시간이 축소되는 경우 현재 이후 유효 점유 슬롯과 충돌하는지 검증 (충돌 시 409
        // SPACE_OPERATING_HOURS_CONFLICT)
        val isHoursShrunk =
            candidateOpening!!.isAfter(space.openingTime!!) ||
                candidateClosing!!.isBefore(space.closingTime!!)
        if (isHoursShrunk) {
            val now = LocalDateTime.now(clock!!)
            val hasConflict =
                occupiedSlotProvider!!.hasOccupiedSlotsOutsideHours(
                    spaceId,
                    candidateOpening,
                    candidateClosing,
                    now,
                )
            if (hasConflict) {
                throw BusinessException(ErrorCode.SPACE_OPERATING_HOURS_CONFLICT)
            }
        }

        val beforeSnapshot = SpaceDetailResponse.from(space)

        space.updateDetail(request)

        val afterSnapshot = SpaceDetailResponse.from(space)

        // 감사 로그 기록 (수정 전후 스냅샷 비교)
        auditLogService!!.log(
            adminMemberId,
            AuditAction.MODIFY_SPACE,
            AuditTargetType.SPACE,
            space.id,
            beforeSnapshot,
            afterSnapshot,
        )

        return afterSnapshot
    }
}
