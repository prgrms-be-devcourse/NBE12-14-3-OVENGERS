package com.ovengers.slotkey.space.image

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockMultipartFile
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import javax.imageio.ImageIO

class SpaceImageStorageTest {
    @TempDir
    lateinit var tempStorageDir: Path

    private lateinit var storage: SpaceImageStorage

    @BeforeEach
    fun setUp() {
        storage = SpaceImageStorage(tempStorageDir.toString())
    }

    private fun createSampleImageBytes(
        format: String,
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = image.createGraphics()
        try {
            g2d.color = Color.BLUE
            g2d.fillRect(0, 0, width, height)
        } finally {
            g2d.dispose()
        }

        val baos = ByteArrayOutputStream()
        ImageIO.write(image, format, baos)
        return baos.toByteArray()
    }

    @Test
    @DisplayName("JPEG 이미지 정상 저장 및 조회 성공")
    fun storeAndLoad_jpeg_success() {
        val bytes = createSampleImageBytes("jpg", 200, 150)
        val file = MockMultipartFile("file", "room.jpg", "image/jpeg", bytes)

        val stored = storage.store(file)

        assertThat(stored.fileName).endsWith(".jpg")
        assertThat(stored.relativeUrl).isEqualTo("/api/v1/space-images/" + stored.fileName)
        assertThat(stored.contentType).isEqualTo("image/jpeg")
        assertThat(stored.width).isEqualTo(200)
        assertThat(stored.height).isEqualTo(150)
        assertThat(stored.size).isEqualTo(bytes.size.toLong())

        val loaded = storage.load(stored.fileName)
        assertThat(loaded.bytes).isEqualTo(bytes)
        assertThat(loaded.contentType).isEqualTo("image/jpeg")
        assertThat(loaded.contentLength).isEqualTo(bytes.size.toLong())
    }

    @Test
    @DisplayName("PNG 이미지 정상 저장 및 조회 성공")
    fun storeAndLoad_png_success() {
        val bytes = createSampleImageBytes("png", 320, 240)
        val file = MockMultipartFile("file", "office.png", "image/png", bytes)

        val stored = storage.store(file)

        assertThat(stored.fileName).endsWith(".png")
        assertThat(stored.relativeUrl).isEqualTo("/api/v1/space-images/" + stored.fileName)
        assertThat(stored.contentType).isEqualTo("image/png")
        assertThat(stored.width).isEqualTo(320)
        assertThat(stored.height).isEqualTo(240)

        val loaded = storage.load(stored.fileName)
        assertThat(loaded.bytes).isEqualTo(bytes)
        assertThat(loaded.contentType).isEqualTo("image/png")
    }

    @Test
    @DisplayName("WebP 이미지는 지원 형식에서 제외되어 INVALID_IMAGE_FORMAT 예외를 발생시킨다")
    fun store_webpFormat_rejected() {
        val webpBytes =
            byteArrayOf(
                'R'.code.toByte(),
                'I'.code.toByte(),
                'F'.code.toByte(),
                'F'.code.toByte(),
                22,
                0,
                0,
                0,
                'W'.code.toByte(),
                'E'.code.toByte(),
                'B'.code.toByte(),
                'P'.code.toByte(),
                'V'.code.toByte(),
                'P'.code.toByte(),
                '8'.code.toByte(),
                ' '.code.toByte(),
            )
        val file = MockMultipartFile("file", "space.webp", "image/webp", webpBytes)

        assertThatThrownBy { storage.store(file) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_FORMAT)
    }

    @Test
    @DisplayName("빈 파일은 IMAGE_FILE_EMPTY 예외를 발생시키고 파일이 생성되지 않는다")
    fun store_emptyFile_throwsException() {
        val emptyFile = MockMultipartFile("file", "empty.jpg", "image/jpeg", byteArrayOf())

        assertThatThrownBy { storage.store(emptyFile) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_FILE_EMPTY)

        Files.list(tempStorageDir).use { stream ->
            assertThat(stream.count()).isZero()
        }
    }

    @Test
    @DisplayName("5MB 초과 파일은 IMAGE_SIZE_EXCEEDED 예외를 발생시키고 파일이 생성되지 않는다")
    fun store_exceedsMaxSize_throwsException() {
        val largeBytes = ByteArray((SpaceImageStorage.MAX_FILE_SIZE + 1).toInt())
        val largeFile = MockMultipartFile("file", "large.jpg", "image/jpeg", largeBytes)

        assertThatThrownBy { storage.store(largeFile) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_SIZE_EXCEEDED)

        Files.list(tempStorageDir).use { stream ->
            assertThat(stream.count()).isZero()
        }
    }

    @Test
    @DisplayName("용량은 5MB 이하이지만 해상도(4096px)를 초과하는 이미지는 디코딩 전 메타데이터 검사에서 IMAGE_DIMENSIONS_EXCEEDED 예외를 발생시킨다")
    fun store_exceedsDimensions_throwsException() {
        // 폭 4097px, 높이 10px의 작은 파일 크기 이미지 (압축 폭탄 시나리오)
        val bytes = createSampleImageBytes("png", 4097, 10)
        assertThat(bytes.size).isLessThan(SpaceImageStorage.MAX_FILE_SIZE.toInt())

        val file = MockMultipartFile("file", "wide.png", "image/png", bytes)

        assertThatThrownBy { storage.store(file) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_DIMENSIONS_EXCEEDED)

        Files.list(tempStorageDir).use { stream ->
            assertThat(stream.count()).isZero()
        }
    }

    @Test
    @DisplayName("해상도 경계값(4096x4096px) 이미지는 정상 저장된다")
    fun store_boundaryDimensions_success() {
        val bytes = createSampleImageBytes("png", 4096, 1)
        val file = MockMultipartFile("file", "boundary.png", "image/png", bytes)

        val stored = storage.store(file)
        assertThat(stored.width).isEqualTo(4096)
        assertThat(stored.height).isEqualTo(1)
    }

    @Test
    @DisplayName("확장자와 실제 서명이 불일치(위조 형식)하면 INVALID_IMAGE_FORMAT 예외를 발생시킨다")
    fun store_mismatchedSignature_throwsException() {
        val pngBytes = createSampleImageBytes("png", 100, 100)
        // PNG 바이트인데 확장자는 jpg
        val file = MockMultipartFile("file", "fake.jpg", "image/jpeg", pngBytes)

        assertThatThrownBy { storage.store(file) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_FORMAT)

        Files.list(tempStorageDir).use { stream ->
            assertThat(stream.count()).isZero()
        }
    }

    @Test
    @DisplayName("손상된 이미지 파일은 INVALID_IMAGE_FORMAT 예외를 발생시킨다")
    fun store_corruptedImage_throwsException() {
        // JPEG 시그니처만 있고 뒤에는 깨진 바이트
        val corrupted = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x01, 0x02)
        val file = MockMultipartFile("file", "corrupt.jpg", "image/jpeg", corrupted)

        assertThatThrownBy { storage.store(file) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_FORMAT)

        Files.list(tempStorageDir).use { stream ->
            assertThat(stream.count()).isZero()
        }
    }

    @Test
    @DisplayName("허용되지 않은 확장자(예: txt, gif, svg)는 INVALID_IMAGE_FORMAT 예외를 발생시킨다")
    fun store_unsupportedExtension_throwsException() {
        val bytes = "not an image".toByteArray()
        val file = MockMultipartFile("file", "script.exe", "application/octet-stream", bytes)

        assertThatThrownBy { storage.store(file) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_FORMAT)

        Files.list(tempStorageDir).use { stream ->
            assertThat(stream.count()).isZero()
        }
    }

    @Test
    @DisplayName("경로 탈출(Path Traversal) 파일명 조회는 IMAGE_NOT_FOUND 예외를 발생시킨다")
    fun load_pathTraversal_throwsException() {
        assertThatThrownBy { storage.load("../secret.txt") }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_NOT_FOUND)

        assertThatThrownBy { storage.load("/etc/passwd") }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_NOT_FOUND)
    }

    @Test
    @DisplayName("심볼릭 링크 파일 조회는 거절되고 IMAGE_NOT_FOUND 예외를 발생시킨다")
    fun load_symbolicLink_throwsException() {
        val realFile = Files.createFile(tempStorageDir.resolve("real-target.jpg"))
        val linkFileName = UUID.randomUUID().toString() + ".jpg"
        val symlink = tempStorageDir.resolve(linkFileName)

        try {
            Files.createSymbolicLink(symlink, realFile)
        } catch (e: UnsupportedOperationException) {
            // OS 또는 파일시스템이 심볼릭 링크 생성을 허용하지 않는 경우 테스트 스킵
            return
        } catch (e: SecurityException) {
            return
        } catch (e: IOException) {
            return
        }

        assertThatThrownBy { storage.load(linkFileName) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_NOT_FOUND)
    }

    @Test
    @DisplayName("존재하지 않는 파일 조회는 IMAGE_NOT_FOUND 예외를 발생시킨다")
    fun load_nonExistentFile_throwsException() {
        val nonExistentFileName = UUID.randomUUID().toString() + ".jpg"

        assertThatThrownBy { storage.load(nonExistentFileName) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_NOT_FOUND)
    }

    @Test
    @DisplayName("파일 삭제 정상 동작 및 비정상 요청 방어")
    fun delete_successAndSecurity() {
        val bytes = createSampleImageBytes("jpg", 100, 100)
        val stored = storage.store("sample.jpg", "image/jpeg", bytes)

        assertThat(storage.delete(stored.fileName)).isTrue()
        assertThat(Files.exists(tempStorageDir.resolve(stored.fileName))).isFalse()

        // 이미 삭제된 파일 재삭제 시 false
        assertThat(storage.delete(stored.fileName)).isFalse()

        // 경로 탈출 시도 삭제 시 false
        assertThat(storage.delete("../outside.jpg")).isFalse()
    }

    @Test
    @DisplayName("서버 관리 이미지 경로 판별")
    fun isServerManagedImage_check() {
        val validPath = "/api/v1/space-images/" + UUID.randomUUID().toString() + ".png"
        assertThat(storage.isServerManagedImage(validPath)).isTrue()

        assertThat(storage.isServerManagedImage("/images/slotkey-test-data/sample.jpg")).isFalse()
        assertThat(storage.isServerManagedImage("https://example.com/photo.png")).isFalse()
        assertThat(storage.isServerManagedImage("/api/v1/space-images/not-a-uuid.jpg")).isFalse()
        assertThat(storage.isServerManagedImage(null)).isFalse()
    }

    @Test
    @DisplayName("SpaceImageProperties 기본값·setter·Storage 생성자 연결을 유지한다")
    fun properties_defaultAndBinding() {
        val properties = SpaceImageProperties()
        assertThat(properties.storageRoot).isEqualTo("./data/space-images")

        properties.storageRoot = tempStorageDir.resolve("configured").toString()
        val configuredStorage = SpaceImageStorage(properties)

        assertThat(configuredStorage.storageRootDir)
            .isEqualTo(Paths.get(properties.storageRoot).toAbsolutePath().normalize())
        assertThat(Files.isDirectory(configuredStorage.storageRootDir)).isTrue()
    }

    @Test
    @DisplayName("null 또는 공백 storageRootPath는 IllegalArgumentException을 발생시킨다")
    fun storageRoot_blankOrNull_throwsException() {
        assertThatThrownBy { SpaceImageStorage(null as String?) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("storageRootPath must not be blank")

        assertThatThrownBy { SpaceImageStorage("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("storageRootPath must not be blank")
    }
}
