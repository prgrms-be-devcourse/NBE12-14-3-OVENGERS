package com.ovengers.slotkey.space.image;

import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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
public class SpaceImageStorage {

    private static final Logger log = LoggerFactory.getLogger(SpaceImageStorage.class);

    public static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    public static final int MAX_WIDTH = 4096;
    public static final int MAX_HEIGHT = 4096;
    public static final String PUBLIC_URL_PREFIX = "/api/v1/space-images/";

    private static final Pattern VALID_FILE_NAME_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(jpg|jpeg|png)$"
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    private final Path storageRootDir;

    @org.springframework.beans.factory.annotation.Autowired
    public SpaceImageStorage(SpaceImageProperties properties) {
        this(properties.getStorageRoot());
    }

    public SpaceImageStorage(String storageRootPath) {
        if (storageRootPath == null || storageRootPath.isBlank()) {
            throw new IllegalArgumentException("storageRootPath must not be blank");
        }
        this.storageRootDir = Paths.get(storageRootPath).toAbsolutePath().normalize();
        initStorageDirectory();
    }

    private void initStorageDirectory() {
        try {
            Files.createDirectories(this.storageRootDir);
        } catch (IOException e) {
            log.error("Failed to create storage directory: {}", storageRootDir, e);
            throw new BusinessException(ErrorCode.IMAGE_STORAGE_ERROR);
        }
    }

    public Path getStorageRootDir() {
        return storageRootDir;
    }

    /**
     * MultipartFile 이미지를 검증하고 영구 디렉터리에 저장합니다.
     */
    public StoredImage store(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() == 0) {
            throw new BusinessException(ErrorCode.IMAGE_FILE_EMPTY);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.IMAGE_SIZE_EXCEEDED);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("Failed to read multipart file bytes", e);
            throw new BusinessException(ErrorCode.IMAGE_STORAGE_ERROR);
        }

        return store(file.getOriginalFilename(), file.getContentType(), bytes);
    }

    /**
     * 바이트 배열과 메타데이터를 기반으로 이미지를 검증하고 저장합니다.
     */
    public StoredImage store(String originalFilename, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.IMAGE_FILE_EMPTY);
        }
        if (bytes.length > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.IMAGE_SIZE_EXCEEDED);
        }

        String rawExt = extractExtension(originalFilename);
        if (rawExt == null || !ALLOWED_EXTENSIONS.contains(rawExt.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE_FORMAT);
        }
        String normalizedExt = rawExt.toLowerCase(Locale.ROOT);
        if ("jpeg".equals(normalizedExt)) {
            normalizedExt = "jpg";
        }

        // 서명 및 Content-Type 검증
        String detectedMimeType = verifySignatureAndMimeType(bytes, normalizedExt, contentType);

        // 이미지 디코딩 및 해상도 검증
        Dimension dimension = verifyImageIntegrityAndDimensions(bytes, normalizedExt);

        // UUID 파일명 생성 및 원자적 저장
        String fileName = UUID.randomUUID().toString() + "." + normalizedExt;
        Path targetPath = storageRootDir.resolve(fileName).normalize();
        ensurePathWithinRoot(targetPath);

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(storageRootDir, "upload-", ".tmp");
            Files.write(tempFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to store image file: {}", targetPath, e);
            // 저장 실패 시 파일 잔여물 제거
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException ignored) {
            }
            throw new BusinessException(ErrorCode.IMAGE_STORAGE_ERROR);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }

        String relativeUrl = PUBLIC_URL_PREFIX + fileName;
        return new StoredImage(
                fileName,
                relativeUrl,
                detectedMimeType,
                bytes.length,
                dimension.width,
                dimension.height
        );
    }

    /**
     * 저장된 이미지를 조회합니다.
     */
    public ImageResource load(String fileName) {
        Path filePath = resolveAndValidateExistingPath(fileName);
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String contentType = determineContentType(fileName);
            Resource resource = new FileSystemResource(filePath);
            return new ImageResource(resource, bytes, contentType, bytes.length);
        } catch (IOException e) {
            log.error("Failed to read image file: {}", filePath, e);
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        }
    }

    /**
     * 저장된 이미지를 삭제합니다.
     */
    public boolean delete(String fileName) {
        if (fileName == null || !VALID_FILE_NAME_PATTERN.matcher(fileName).matches()) {
            return false;
        }

        Path filePath = storageRootDir.resolve(fileName).normalize();
        if (!filePath.startsWith(storageRootDir) || filePath.equals(storageRootDir)) {
            return false;
        }
        if (Files.isSymbolicLink(filePath)) {
            return false;
        }

        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("Failed to delete image file: {}", filePath, e);
            return false;
        }
    }

    /**
     * 해당 imagePath가 서버가 관리하는 공개 이미지 상대 경로인지 확인합니다.
     */
    public boolean isServerManagedImage(String imagePath) {
        if (imagePath == null || !imagePath.startsWith(PUBLIC_URL_PREFIX)) {
            return false;
        }
        String fileName = extractFileNameFromPath(imagePath);
        return fileName != null && VALID_FILE_NAME_PATTERN.matcher(fileName).matches();
    }

    /**
     * 상대 URL 경로에서 파일명을 추출합니다.
     */
    public String extractFileNameFromPath(String imagePath) {
        if (imagePath == null) {
            return null;
        }
        int lastSlash = imagePath.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == imagePath.length() - 1) {
            return null;
        }
        return imagePath.substring(lastSlash + 1);
    }

    private Path resolveAndValidateExistingPath(String fileName) {
        if (fileName == null || !VALID_FILE_NAME_PATTERN.matcher(fileName).matches()) {
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        }

        Path filePath = storageRootDir.resolve(fileName).normalize();
        ensurePathWithinRoot(filePath);

        if (Files.isSymbolicLink(filePath)) {
            log.warn("Rejected access to symbolic link: {}", filePath);
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        }

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        }

        return filePath;
    }

    private void ensurePathWithinRoot(Path path) {
        if (!path.startsWith(storageRootDir) || path.equals(storageRootDir)) {
            log.warn("Path traversal attempt detected: {}", path);
            throw new BusinessException(ErrorCode.IMAGE_NOT_FOUND);
        }
    }

    private String extractExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return null;
        }
        return filename.substring(dotIndex + 1);
    }

    private String verifySignatureAndMimeType(byte[] bytes, String normalizedExt, String contentType) {
        boolean isJpeg = isJpegSignature(bytes);
        boolean isPng = isPngSignature(bytes);

        if (!isJpeg && !isPng) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE_FORMAT);
        }

        // 실제 서명과 확장자 일치 확인
        if (isJpeg && !("jpg".equals(normalizedExt) || "jpeg".equals(normalizedExt))) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE_FORMAT);
        }
        if (isPng && !"png".equals(normalizedExt)) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE_FORMAT);
        }

        // Content-Type 이 주어진 경우 서명과 상충하지 않는지 확인
        String expectedMimeType = isJpeg ? "image/jpeg" : "image/png";
        if (contentType != null && !contentType.isBlank() && !"application/octet-stream".equalsIgnoreCase(contentType)) {
            String trimmedContentType = contentType.trim().toLowerCase(Locale.ROOT);
            if (!trimmedContentType.equalsIgnoreCase(expectedMimeType)) {
                log.warn("Mismatched Content-Type. Expected: {}, Provided: {}", expectedMimeType, contentType);
                throw new BusinessException(ErrorCode.INVALID_IMAGE_FORMAT);
            }
        }

        return expectedMimeType;
    }

    private boolean isJpegSignature(byte[] bytes) {
        if (bytes.length < 3) return false;
        return (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private boolean isPngSignature(byte[] bytes) {
        if (bytes.length < 8) return false;
        return (bytes[0] & 0xFF) == 0x89
                && (bytes[1] & 0xFF) == 0x50
                && (bytes[2] & 0xFF) == 0x4E
                && (bytes[3] & 0xFF) == 0x47
                && (bytes[4] & 0xFF) == 0x0D
                && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A
                && (bytes[7] & 0xFF) == 0x0A;
    }

    private Dimension verifyImageIntegrityAndDimensions(byte[] bytes, String normalizedExt) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                throw new BusinessException(ErrorCode.INVALID_IMAGE_FORMAT);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.INVALID_IMAGE_FORMAT);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, false);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new BusinessException(ErrorCode.INVALID_IMAGE_FORMAT);
                }
                // 압축 해제 전 이미지 메타데이터로 픽셀 상한 검증 (압축 폭탄 방어)
                if (width > MAX_WIDTH || height > MAX_HEIGHT) {
                    log.warn("Image resolution exceeded limit: {}x{}", width, height);
                    throw new BusinessException(ErrorCode.IMAGE_DIMENSIONS_EXCEEDED);
                }
                // 메타데이터 검증 통과 후 전체 픽셀 디코딩을 수행하여 파일 손상/절단 여부 검증
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new BusinessException(ErrorCode.INVALID_IMAGE_FORMAT);
                }
                return new Dimension(width, height);
            } finally {
                reader.dispose();
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("Corrupted or unreadable image file decode failure", e);
            throw new BusinessException(ErrorCode.INVALID_IMAGE_FORMAT);
        }
    }

    private String determineContentType(String fileName) {
        String ext = extractExtension(fileName);
        if (ext == null) return "application/octet-stream";
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            default -> "application/octet-stream";
        };
    }
}
