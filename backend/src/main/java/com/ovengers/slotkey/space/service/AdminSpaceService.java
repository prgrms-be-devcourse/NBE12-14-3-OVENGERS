package com.ovengers.slotkey.space.service;

import com.ovengers.slotkey.audit.entity.AuditAction;
import com.ovengers.slotkey.audit.entity.AuditTargetType;
import com.ovengers.slotkey.audit.service.AuditLogService;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.space.dto.request.SpaceCreateRequest;
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest;
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse;
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

@Service
public class AdminSpaceService {

    private final SpaceRepository spaceRepository;
    private final AuditLogService auditLogService;
    private final OccupiedSlotProvider occupiedSlotProvider;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<SpaceDetailResponse> getSpaces(SpaceStatus status, String keyword, Pageable pageable) {
        String normalizedKeyword = normalize(keyword);
        return spaceRepository.searchSpacesByNameOrDescription(normalizedKeyword, status, pageable)
                .map(SpaceDetailResponse::from);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional(readOnly = true)
    public Space getSpaceDetailById(Long spaceId) {
        return spaceRepository.findById(spaceId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));
    }

    @Transactional
    public SpaceDetailResponse createSpace(SpaceCreateRequest request, Long adminMemberId) {
        Space space = request.toEntity();
        Space savedSpace = spaceRepository.save(space);

        SpaceDetailResponse response = SpaceDetailResponse.from(savedSpace);

        // 감사 로그 기록
        auditLogService.log(
                adminMemberId,
                AuditAction.REGISTER_SPACE,
                AuditTargetType.SPACE,
                savedSpace.getId(),
                null,
                response);

        return response;
    }

    @Transactional
    public SpaceDetailResponse updateSpace(Long spaceId, SpaceUpdateRequest request, Long adminMemberId) {
        Space space = spaceRepository.findByIdForUpdate(spaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));

        // 1. 후보 운영시간 계산 및 유효성(순서 및 30분 단위) 사전 검증 (실패 시 400 INVALID_OPERATING_HOURS)
        LocalTime candidateOpening = request.openingTime() != null ? request.openingTime() : space.getOpeningTime();
        LocalTime candidateClosing = request.closingTime() != null ? request.closingTime() : space.getClosingTime();
        if (request.openingTime() != null || request.closingTime() != null) {
            Space.validateOperatingHours(candidateOpening, candidateClosing);
        }

        // 2. 운영시간이 축소되는 경우 현재 이후 유효 점유 슬롯과 충돌하는지 검증 (충돌 시 409
        // SPACE_OPERATING_HOURS_CONFLICT)
        boolean isHoursShrunk = candidateOpening.isAfter(space.getOpeningTime())
                || candidateClosing.isBefore(space.getClosingTime());
        if (isHoursShrunk) {
            LocalDateTime now = LocalDateTime.now(clock);
            boolean hasConflict = occupiedSlotProvider.hasOccupiedSlotsOutsideHours(
                    spaceId, candidateOpening, candidateClosing, now);
            if (hasConflict) {
                throw new BusinessException(ErrorCode.SPACE_OPERATING_HOURS_CONFLICT);
            }
        }

        SpaceDetailResponse beforeSnapshot = SpaceDetailResponse.from(space);

        space.updateDetail(request);

        SpaceDetailResponse afterSnapshot = SpaceDetailResponse.from(space);

        // 감사 로그 기록 (수정 전후 스냅샷 비교)
        auditLogService.log(
                adminMemberId,
                AuditAction.MODIFY_SPACE,
                AuditTargetType.SPACE,
                space.getId(),
                beforeSnapshot,
                afterSnapshot);

        return afterSnapshot;
    }

    public AdminSpaceService(
            final SpaceRepository spaceRepository,
            final AuditLogService auditLogService,
            final OccupiedSlotProvider occupiedSlotProvider,
            final Clock clock) {
        this.spaceRepository = spaceRepository;
        this.auditLogService = auditLogService;
        this.occupiedSlotProvider = occupiedSlotProvider;
        this.clock = clock;
    }
}
