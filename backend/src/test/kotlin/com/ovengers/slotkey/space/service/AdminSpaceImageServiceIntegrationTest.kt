package com.ovengers.slotkey.space.service

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.image.SpaceImageStorage
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.mock.web.MockMultipartFile
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class AdminSpaceImageServiceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var spaceImageService: SpaceImageService

    @SpyBean
    private lateinit var adminSpaceImageUpdateService: AdminSpaceImageUpdateService

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @Autowired
    private lateinit var spaceImageStorage: SpaceImageStorage

    private val testAdminMemberId = 1L

    @BeforeEach
    fun setUp() {
        auditLogRepository.deleteAll()
        spaceRepository.deleteAll()
        val rootDir = spaceImageStorage.storageRootDir
        if (Files.exists(rootDir)) {
            Files.list(rootDir).use { stream ->
                stream.forEach { path ->
                    try {
                        Files.deleteIfExists(path)
                    } catch (ignored: IOException) {
                        // 정리 중 삭제 실패는 무시한다 (원본과 동일한 처리)
                    }
                }
            }
        }
    }

    private fun createTestSpace(initialImagePath: String): Space {
        val space: Space =
            Space
                .builder()
                .name("공간 이미지 테스트 회의실")
                .location("서울시 강남구 테헤란로 123")
                .description("테스트용 회의실")
                .capacity(8)
                .pricePerSlot(15000L)
                .imagePath(initialImagePath)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(22, 0))
                .status(SpaceStatus.ACTIVE)
                .build()
        return spaceRepository.save(space)
    }

    private fun createSamplePngBytes(
        width: Int,
        height: Int,
        color: Color,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()
        g2d.color = color
        g2d.fillRect(0, 0, width, height)
        g2d.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return baos.toByteArray()
    }

    @Test
    @DisplayName("새 이미지 업로드 시 Space.imagePath가 갱신되고 MODIFY_SPACE 감사 로그가 원자적으로 기록된다 (version 불변)")
    fun uploadAndAttach_success() {
        val space = createTestSpace("/images/slotkey-test-data/sample1.jpg")
        val initialVersion = space.version

        val imageBytes = createSamplePngBytes(200, 200, Color.GREEN)
        val file =
            MockMultipartFile(
                "file",
                "new-space.png",
                "image/png",
                imageBytes,
            )

        val response = spaceImageService.uploadAndAttachSpaceImage(space.id!!, file, testAdminMemberId)!!

        assertThat(response.imagePath).startsWith("/api/v1/space-images/")
        assertThat(response.imagePath).endsWith(".png")

        // DB 검증
        val updatedSpace = spaceRepository.findById(space.id!!).orElseThrow()
        assertThat(updatedSpace.imagePath).isEqualTo(response.imagePath)
        // 사진 변경만으로 version은 증가하지 않아야 함
        assertThat(updatedSpace.version).isEqualTo(initialVersion)

        // 감사 로그 검증
        val auditLogs: List<AuditLog> = auditLogRepository.findAll()
        assertThat(auditLogs).hasSize(1)
        val auditLog = auditLogs[0]
        assertThat(auditLog.action).isEqualTo(AuditAction.MODIFY_SPACE)
        assertThat(auditLog.targetType).isEqualTo(AuditTargetType.SPACE)
        assertThat(auditLog.targetId).isEqualTo(space.id)
        assertThat(auditLog.actorMemberId).isEqualTo(testAdminMemberId)
        assertThat(auditLog.beforeValue).contains("/images/slotkey-test-data/sample1.jpg")
        assertThat(auditLog.afterValue).contains(response.imagePath)

        // 실제 파일 존재 검증
        val newFileName = spaceImageStorage.extractFileNameFromPath(response.imagePath)
        val physicalFile: Path = spaceImageStorage.storageRootDir.resolve(newFileName!!)
        assertThat(Files.exists(physicalFile)).isTrue()
    }

    @Test
    @DisplayName("DB 트랜잭션 실패 시 저장된 새 파일이 물리적으로 롤백/제거된다")
    fun uploadAndAttach_dbFailure_cleansUpNewFile() {
        val space = createTestSpace("/images/slotkey-test-data/sample1.jpg")

        val imageBytes = createSamplePngBytes(150, 150, Color.RED)
        val file =
            MockMultipartFile(
                "file",
                "will-fail.png",
                "image/png",
                imageBytes,
            )

        // DB 트랜잭션에서 예외 강제 발생
        doThrow(RuntimeException("DB Connection Timeout Simulation"))
            .`when`(adminSpaceImageUpdateService)
            .updateSpaceImage(anyLong(), anyString(), anyLong())

        assertThatThrownBy { spaceImageService.uploadAndAttachSpaceImage(space.id!!, file, testAdminMemberId) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("DB Connection Timeout Simulation")

        // DB 상태 불변 검증
        val unchangedSpace = spaceRepository.findById(space.id!!).orElseThrow()
        assertThat(unchangedSpace.imagePath).isEqualTo("/images/slotkey-test-data/sample1.jpg")

        // 감사 로그 없음 검증
        assertThat(auditLogRepository.count()).isZero()

        // 디렉터리에 새로 업로드된 파일이 남아있지 않은지 검증
        Files.list(spaceImageStorage.storageRootDir).use { stream ->
            assertThat(stream.count()).isZero()
        }
    }

    @Test
    @DisplayName("기존 서버 관리 이미지가 교체되면 이전 파일은 삭제되고 샘플 경로는 삭제 대상에서 제외된다")
    fun uploadAndAttach_replaceServerManagedImage_deletesOldFile() {
        // 1. 처음엔 샘플 이미지
        val space = createTestSpace("/images/slotkey-test-data/sample1.jpg")

        // 2. 첫 번째 서버 이미지 업로드
        val firstBytes = createSamplePngBytes(100, 100, Color.MAGENTA)
        val firstFile =
            MockMultipartFile(
                "file",
                "first.png",
                "image/png",
                firstBytes,
            )
        val firstResponse = spaceImageService.uploadAndAttachSpaceImage(space.id!!, firstFile, testAdminMemberId)!!
        val firstFileName = spaceImageStorage.extractFileNameFromPath(firstResponse.imagePath)!!
        val firstPhysicalFile: Path = spaceImageStorage.storageRootDir.resolve(firstFileName)
        assertThat(Files.exists(firstPhysicalFile)).isTrue()

        // 3. 두 번째 서버 이미지로 교체 업로드
        val secondBytes = createSamplePngBytes(120, 120, Color.ORANGE)
        val secondFile =
            MockMultipartFile(
                "file",
                "second.png",
                "image/png",
                secondBytes,
            )
        val secondResponse = spaceImageService.uploadAndAttachSpaceImage(space.id!!, secondFile, testAdminMemberId)!!
        val secondFileName = spaceImageStorage.extractFileNameFromPath(secondResponse.imagePath)!!
        val secondPhysicalFile: Path = spaceImageStorage.storageRootDir.resolve(secondFileName)

        // 새 파일은 존재하고, 이전 파일은 삭제되었어야 함
        assertThat(Files.exists(secondPhysicalFile)).isTrue()
        assertThat(Files.exists(firstPhysicalFile)).isFalse()

        // DB 상의 최종 이미지 경로 확인
        val finalSpace = spaceRepository.findById(space.id!!).orElseThrow()
        assertThat(finalSpace.imagePath).isEqualTo(secondResponse.imagePath)
    }

    @Test
    @DisplayName("동시 다중 이미지 교체 요청 시 최종 Space.imagePath가 가리키는 실제 파일이 디스크에 반드시 존재한다")
    fun uploadAndAttach_concurrency() {
        val space = createTestSpace("/images/slotkey-test-data/sample1.jpg")
        val threadCount = 4
        val executorService: ExecutorService = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        val futures = ArrayList<Future<SpaceDetailResponse?>>()

        for (i in 0 until threadCount) {
            val index = i
            val bytes = createSamplePngBytes(50 + index * 10, 50 + index * 10, Color.DARK_GRAY)
            val file =
                MockMultipartFile(
                    "file",
                    "concurrent-$index.png",
                    "image/png",
                    bytes,
                )
            futures.add(
                executorService.submit(
                    Callable {
                        startLatch.await()
                        try {
                            spaceImageService.uploadAndAttachSpaceImage(space.id!!, file, testAdminMemberId)
                        } finally {
                            doneLatch.countDown()
                        }
                    },
                ),
            )
        }

        startLatch.countDown()
        val completed = doneLatch.await(10, TimeUnit.SECONDS)
        assertThat(completed).isTrue()
        executorService.shutdown()

        // 최종 Space 조회
        val finalSpace = spaceRepository.findById(space.id!!).orElseThrow()
        val finalImagePath = finalSpace.imagePath
        assertThat(finalImagePath).startsWith("/api/v1/space-images/")

        val finalFileName = spaceImageStorage.extractFileNameFromPath(finalImagePath)!!
        val finalPhysicalFile: Path = spaceImageStorage.storageRootDir.resolve(finalFileName)
        assertThat(Files.exists(finalPhysicalFile)).isTrue()

        // 모든 스레드가 감사 로그를 남겼는지 확인
        val auditCount = auditLogRepository.count()
        assertThat(auditCount).isEqualTo(threadCount.toLong())
    }
}
