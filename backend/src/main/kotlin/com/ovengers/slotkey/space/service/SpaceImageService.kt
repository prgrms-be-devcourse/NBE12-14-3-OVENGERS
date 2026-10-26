package com.ovengers.slotkey.space.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse
import com.ovengers.slotkey.space.image.SpaceImageStorage
import com.ovengers.slotkey.space.image.StoredImage
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

/**
 * 공간 대표 이미지 업로드 및 파일-DB 정합성 조정 서비스.
 *
 * - DB 트랜잭션과 파일 저장소는 단일 원자 트랜잭션이 아니므로 다음 불변식을 보장합니다:
 *   1. 파일 저장 성공 후 DB 트랜잭션이 실패하면 새로 생성된 파일을 즉시 삭제합니다.
 *   2. DB 트랜잭션이 완전히 커밋된 이후에만 이전 서버 관리 이미지를 삭제합니다.
 *   3. 기존 샘플 이미지(/images/...)나 외부 URL 경로는 삭제 대상에서 제외합니다.
 */
@Service
class SpaceImageService(
    private val spaceImageStorage: SpaceImageStorage?,
    private val adminSpaceImageUpdateService: AdminSpaceImageUpdateService?,
    private val spaceRepository: SpaceRepository?,
) {
    private val log: Logger = LoggerFactory.getLogger(SpaceImageService::class.java)

    fun uploadAndAttachSpaceImage(
        spaceId: Long?,
        file: MultipartFile?,
        adminMemberId: Long?,
    ): SpaceDetailResponse? {
        // 0. 대상 공간 존재 여부 사전 검증
        if (!spaceRepository!!.existsById(spaceId)) {
            throw BusinessException(ErrorCode.SPACE_NOT_FOUND)
        }

        // 1. 새 파일 디스크 저장 및 서명/해상도 검증
        val storedImage: StoredImage = spaceImageStorage!!.store(file)
        val newImagePath: String = storedImage.relativeUrl
        val newFileName: String = storedImage.fileName

        val updateResult: ImageUpdateResult
        try {
            // 2. 별도 트랜잭션 서비스에서 Space 잠금 및 감사 로그 원자적 기록
            updateResult = adminSpaceImageUpdateService!!.updateSpaceImage(spaceId, newImagePath, adminMemberId)
        } catch (e: RuntimeException) {
            // DB 실패 또는 커밋 실패 시 새로 생성된 파일 정리
            log.error("Failed to commit image update to DB. Cleaning up newly stored file: {}", newFileName, e)
            try {
                spaceImageStorage.delete(newFileName)
            } catch (deleteEx: Exception) {
                log.error("Failed to cleanup new file after DB failure: {}", newFileName, deleteEx)
            }
            throw e
        }

        // 3. 커밋 성공 후: 교체된 이전 이미지가 서버 관리 경로인 경우에만 파일 삭제
        val oldImagePath: String? = updateResult.oldImagePath
        if (spaceImageStorage.isServerManagedImage(oldImagePath)) {
            val oldFileName: String? = spaceImageStorage.extractFileNameFromPath(oldImagePath)
            if (oldFileName != null && oldFileName != newFileName) {
                try {
                    val deleted: Boolean = spaceImageStorage.delete(oldFileName)
                    if (!deleted) {
                        log.warn("Old image file could not be deleted (might not exist): {}", oldFileName)
                    }
                } catch (e: Exception) {
                    log.warn("Error while deleting old image file: {}", oldFileName, e)
                }
            }
        }

        return updateResult.spaceDetailResponse
    }
}
