package com.ovengers.slotkey.space.service;

import com.ovengers.slotkey.audit.entity.AuditAction;
import com.ovengers.slotkey.audit.entity.AuditTargetType;
import com.ovengers.slotkey.audit.service.AuditLogService;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공간 대표 이미지 변경 트랜잭션 서비스.
 *
 * - Space 비관적 쓰기 잠금(PESSIMISTIC_WRITE)을 획득하여 동시 수정을 직렬화합니다.
 * - 사진 변경은 가격 비교용 Space.version 을 증가시키지 않습니다.
 * - Space 엔티티 수정과 MODIFY_SPACE 감사 로그는 동일한 DB 트랜잭션에서 원자적으로 커밋/롤백됩니다.
 */
@Service
public class AdminSpaceImageUpdateService {

    private final SpaceRepository spaceRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public ImageUpdateResult updateSpaceImage(Long spaceId, String newImagePath, Long adminMemberId) {
        Space space = spaceRepository.findByIdForUpdate(spaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));

        String oldImagePath = space.getImagePath();
        SpaceDetailResponse beforeSnapshot = SpaceDetailResponse.from(space);

        space.updateImagePath(newImagePath);

        SpaceDetailResponse afterSnapshot = SpaceDetailResponse.from(space);

        // 감사 로그 기록 (수정 전후 스냅샷 비교)
        auditLogService.log(
                adminMemberId,
                AuditAction.MODIFY_SPACE,
                AuditTargetType.SPACE,
                space.getId(),
                beforeSnapshot,
                afterSnapshot
        );

        return new ImageUpdateResult(oldImagePath, afterSnapshot);
    }

    public AdminSpaceImageUpdateService(
            final SpaceRepository spaceRepository,
            final AuditLogService auditLogService) {
        this.spaceRepository = spaceRepository;
        this.auditLogService = auditLogService;
    }
}
