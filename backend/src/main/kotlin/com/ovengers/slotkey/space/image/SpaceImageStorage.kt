package com.ovengers.slotkey.space.image

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.FileSystemResource
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.awt.Dimension
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import javax.imageio.ImageIO

/**
 * 공간 대표 이미지 파일 저장 및 검증 계층.
 *
 * - 원본 파일명을 신뢰하지 않고 서버 생성 UUID와 검증된 확장자로 저장합니다.
 * - 지원 형식: JPEG, PNG (단일 프레임/정적 이미지)
 * - 최대 용량: 5MB
 * - 최대 해상도: 4096 x 4096
 * - 경로 탈출(Path Traversal), 심볼릭 링크 참조를 엄격히 차단합니다.
 */
@Component
class SpaceImageStorage(
    storageRootPath: String?,
) {
    val storageRootDir: Path = resolveStorageRoot(storageRootPath)

    init {
        initStorageDirectory()
    }

    @Autowired
    constructor(properties: SpaceImageProperties) : this(properties.storageRoot)

    private fun initStorageDirectory() {
        try {
            Files.createDirectories(storageRootDir)
        } catch (e: IOException) {
            log.error("Failed to create storage directory: {}", storageRootDir, e)
            throw BusinessException(ErrorCode.IMAGE_STORAGE_ERROR)
        }
    }

    /**
     * MultipartFile 이미지를 검증하고 영구 디렉터리에 저장합니다.
     */
    fun store(file: MultipartFile?): StoredImage {
        if (file == null || file.isEmpty || file.size == 0L) {
            throw BusinessException(ErrorCode.IMAGE_FILE_EMPTY)
        }
        if (file.size > MAX_FILE_SIZE) {
            throw BusinessException(ErrorCode.IMAGE_SIZE_EXCEEDED)
        }

        val bytes =
            try {
                file.bytes
            } catch (e: IOException) {
                log.error("Failed to read multipart file bytes", e)
                throw BusinessException(ErrorCode.IMAGE_STORAGE_ERROR)
            }

        return store(file.originalFilename, file.contentType, bytes)
    }

    /**
     * 바이트 배열과 메타데이터를 기반으로 이미지를 검증하고 저장합니다.
     */
    fun store(
        originalFilename: String?,
        contentType: String?,
        bytes: ByteArray?,
    ): StoredImage {
        if (bytes == null || bytes.isEmpty()) {
            throw BusinessException(ErrorCode.IMAGE_FILE_EMPTY)
        }
        if (bytes.size > MAX_FILE_SIZE) {
            throw BusinessException(ErrorCode.IMAGE_SIZE_EXCEEDED)
        }

        val rawExt = extractExtension(originalFilename)
        if (rawExt == null || rawExt.lowercase(Locale.ROOT) !in ALLOWED_EXTENSIONS) {
            throw BusinessException(ErrorCode.INVALID_IMAGE_FORMAT)
        }
        val normalizedExt = rawExt.lowercase(Locale.ROOT).let { if (it == "jpeg") "jpg" else it }

        // 서명 및 Content-Type 검증
        val detectedMimeType = verifySignatureAndMimeType(bytes, normalizedExt, contentType)

        // 이미지 디코딩 및 해상도 검증
        val dimension = verifyImageIntegrityAndDimensions(bytes)

        // UUID 파일명 생성 및 원자적 저장
        val fileName = "${UUID.randomUUID()}.$normalizedExt"
        val targetPath = resolveAndValidatePath(fileName)

        var tempFile: Path? = null
        try {
            tempFile = Files.createTempFile(storageRootDir, "upload-", ".tmp")
            Files.write(tempFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(
                    tempFile,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (ignored: AtomicMoveNotSupportedException) {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            log.error("Failed to store image file: {}", targetPath, e)
            // 저장 실패 시 파일 잔여물 제거
            try {
                Files.deleteIfExists(targetPath)
            } catch (ignored: IOException) {
            }
            throw BusinessException(ErrorCode.IMAGE_STORAGE_ERROR)
        } finally {
            tempFile?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (ignored: IOException) {
                }
            }
        }

        val relativeUrl = PUBLIC_URL_PREFIX + fileName
        return StoredImage(
            fileName,
            relativeUrl,
            detectedMimeType,
            bytes.size.toLong(),
            dimension.width,
            dimension.height,
        )
    }

    /**
     * 저장된 이미지를 조회합니다.
     */
    fun load(fileName: String?): ImageResource {
        val validFileName = validateFileName(fileName)
        val filePath = resolveAndValidateExistingPath(validFileName)
        return try {
            val bytes = Files.readAllBytes(filePath)
            val contentType = determineContentType(validFileName)
            ImageResource(FileSystemResource(filePath), bytes, contentType, bytes.size.toLong())
        } catch (e: IOException) {
            log.error("Failed to read image file: {}", filePath, e)
            throw BusinessException(ErrorCode.IMAGE_NOT_FOUND)
        }
    }

    /**
     * 저장된 이미지를 삭제합니다.
     */
    fun delete(fileName: String?): Boolean {
        if (fileName == null || !VALID_FILE_NAME_PATTERN.matcher(fileName).matches()) {
            return false
        }

        val filePath = storageRootDir.resolve(fileName).normalize()
        if (!filePath.startsWith(storageRootDir) || filePath == storageRootDir) {
            return false
        }
        if (Files.isSymbolicLink(filePath)) {
            return false
        }

        return try {
            Files.deleteIfExists(filePath)
        } catch (e: IOException) {
            log.warn("Failed to delete image file: {}", filePath, e)
            false
        }
    }

    /**
     * 해당 imagePath가 서버가 관리하는 공개 이미지 상대 경로인지 확인합니다.
     */
    fun isServerManagedImage(imagePath: String?): Boolean {
        if (imagePath == null || !imagePath.startsWith(PUBLIC_URL_PREFIX)) {
            return false
        }
        val fileName = extractFileNameFromPath(imagePath) ?: return false
        return VALID_FILE_NAME_PATTERN.matcher(fileName).matches()
    }

    /**
     * 상대 URL 경로에서 파일명을 추출합니다.
     */
    fun extractFileNameFromPath(imagePath: String?): String? {
        if (imagePath == null) {
            return null
        }
        val lastSlash = imagePath.lastIndexOf('/')
        if (lastSlash < 0 || lastSlash == imagePath.length - 1) {
            return null
        }
        return imagePath.substring(lastSlash + 1)
    }

    private fun validateFileName(fileName: String?): String {
        if (fileName == null || isJavaBlank(fileName) || !VALID_FILE_NAME_PATTERN.matcher(fileName).matches()) {
            throw BusinessException(ErrorCode.IMAGE_NOT_FOUND)
        }
        return fileName
    }

    private fun resolveAndValidatePath(fileName: String): Path {
        val filePath = storageRootDir.resolve(fileName).normalize()
        ensurePathWithinRoot(filePath)
        return filePath
    }

    private fun resolveAndValidateExistingPath(fileName: String): Path {
        val filePath = resolveAndValidatePath(fileName)

        if (Files.isSymbolicLink(filePath)) {
            log.warn("Rejected access to symbolic link: {}", filePath)
            throw BusinessException(ErrorCode.IMAGE_NOT_FOUND)
        }

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw BusinessException(ErrorCode.IMAGE_NOT_FOUND)
        }

        return filePath
    }

    private fun ensurePathWithinRoot(path: Path) {
        if (!path.startsWith(storageRootDir) || path == storageRootDir) {
            log.warn("Path traversal attempt detected: {}", path)
            throw BusinessException(ErrorCode.IMAGE_NOT_FOUND)
        }
    }

    private fun extractExtension(filename: String?): String? {
        if (filename == null) {
            return null
        }
        val dotIndex = filename.lastIndexOf('.')
        if (dotIndex < 0 || dotIndex == filename.length - 1) {
            return null
        }
        return filename.substring(dotIndex + 1)
    }

    private fun verifySignatureAndMimeType(
        bytes: ByteArray,
        normalizedExt: String,
        contentType: String?,
    ): String {
        val isJpeg = isJpegSignature(bytes)
        val isPng = isPngSignature(bytes)

        if (!isJpeg && !isPng) {
            throw BusinessException(ErrorCode.INVALID_IMAGE_FORMAT)
        }

        // 실제 서명과 확장자 일치 확인
        if (isJpeg && normalizedExt != "jpg" && normalizedExt != "jpeg") {
            throw BusinessException(ErrorCode.INVALID_IMAGE_FORMAT)
        }
        if (isPng && normalizedExt != "png") {
            throw BusinessException(ErrorCode.INVALID_IMAGE_FORMAT)
        }

        // Content-Type 이 주어진 경우 서명과 상충하지 않는지 확인
        val expectedMimeType = if (isJpeg) "image/jpeg" else "image/png"
        if (contentType != null && !isJavaBlank(contentType) && !"application/octet-stream".equals(contentType, ignoreCase = true)) {
            val trimmedContentType = contentType.trim().lowercase(Locale.ROOT)
            if (!trimmedContentType.equals(expectedMimeType, ignoreCase = true)) {
                log.warn("Mismatched Content-Type. Expected: {}, Provided: {}", expectedMimeType, contentType)
                throw BusinessException(ErrorCode.INVALID_IMAGE_FORMAT)
            }
        }

        return expectedMimeType
    }

    private fun isJpegSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        return (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF
    }

    private fun isPngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return (bytes[0].toInt() and 0xFF) == 0x89 &&
            (bytes[1].toInt() and 0xFF) == 0x50 &&
            (bytes[2].toInt() and 0xFF) == 0x4E &&
            (bytes[3].toInt() and 0xFF) == 0x47 &&
            (bytes[4].toInt() and 0xFF) == 0x0D &&
            (bytes[5].toInt() and 0xFF) == 0x0A &&
            (bytes[6].toInt() and 0xFF) == 0x1A &&
            (bytes[7].toInt() and 0xFF) == 0x0A
    }

    private fun verifyImageIntegrityAndDimensions(bytes: ByteArray): Dimension =
        try {
            val iis =
                ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
                    ?: throw BusinessException(ErrorCode.INVALID_IMAGE_FORMAT)
            try {
                val readers = ImageIO.getImageReaders(iis)
                if (!readers.hasNext()) {
                    throw BusinessException(ErrorCode.INVALID_IMAGE_FORMAT)
                }
                val reader = readers.next()
                try {
                    reader.setInput(iis, true, false)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0) {
                        throw BusinessException(ErrorCode.INVALID_IMAGE_FORMAT)
                    }
                    // 압축 해제 전 이미지 메타데이터로 픽셀 상한 검증 (압축 폭탄 방어)
                    if (width > MAX_WIDTH || height > MAX_HEIGHT) {
                        log.warn("Image resolution exceeded limit: {}x{}", width, height)
                        throw BusinessException(ErrorCode.IMAGE_DIMENSIONS_EXCEEDED)
                    }
                    // 메타데이터 검증 통과 후 전체 픽셀 디코딩을 수행하여 파일 손상/절단 여부 검증
                    val image = reader.read(0)
                    if (image == null) {
                        throw BusinessException(ErrorCode.INVALID_IMAGE_FORMAT)
                    }
                    Dimension(width, height)
                } finally {
                    reader.dispose()
                }
            } finally {
                iis.close()
            }
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.warn("Corrupted or unreadable image file decode failure", e)
            throw BusinessException(ErrorCode.INVALID_IMAGE_FORMAT)
        }

    private fun determineContentType(fileName: String): String {
        val ext = extractExtension(fileName) ?: return "application/octet-stream"
        return when (ext.lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SpaceImageStorage::class.java)

        const val MAX_FILE_SIZE: Long = 5L * 1024 * 1024 // 5MB
        const val MAX_WIDTH: Int = 4096
        const val MAX_HEIGHT: Int = 4096
        const val PUBLIC_URL_PREFIX: String = "/api/v1/space-images/"

        private val VALID_FILE_NAME_PATTERN: Pattern =
            Pattern.compile(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(jpg|jpeg|png)$",
            )

        private val ALLOWED_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png")

        private fun resolveStorageRoot(storageRootPath: String?): Path {
            if (storageRootPath == null || isJavaBlank(storageRootPath)) {
                throw IllegalArgumentException("storageRootPath must not be blank")
            }
            return Paths.get(storageRootPath).toAbsolutePath().normalize()
        }

        private fun isJavaBlank(value: String): Boolean = value.codePoints().allMatch { Character.isWhitespace(it) }
    }
}
