package com.ovengers.slotkey.space.controller

import com.ovengers.slotkey.space.image.SpaceImageStorage
import com.ovengers.slotkey.space.image.StoredImage
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO

@AutoConfigureMockMvc
class SpaceImageControllerTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var spaceImageStorage: SpaceImageStorage

    @BeforeEach
    fun setUp() {
        val rootDir: Path = spaceImageStorage.storageRootDir
        if (Files.exists(rootDir)) {
            Files.list(rootDir).use { stream ->
                stream.forEach { path ->
                    try {
                        Files.deleteIfExists(path)
                    } catch (_: IOException) {
                    }
                }
            }
        }
    }

    private fun createSampleJpegBytes(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d: Graphics2D = image.createGraphics()
        g2d.color = Color.CYAN
        g2d.fillRect(0, 0, width, height)
        g2d.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", baos)
        return baos.toByteArray()
    }

    @Test
    @DisplayName("비인가 사용자도 GET /api/v1/space-images/{fileName}을 통해 이미지를 조회할 수 있다")
    fun getImage_unauthenticated_success() {
        val bytes = createSampleJpegBytes(100, 100)
        val file = MockMultipartFile("file", "office.jpg", "image/jpeg", bytes)
        val stored: StoredImage = spaceImageStorage.store(file)

        mockMvc
            .perform(get("/api/v1/space-images/{fileName}", stored.fileName))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Cache-Control", "max-age=86400, public"))
            .andExpect(content().bytes(bytes))
    }

    @Test
    @DisplayName("존재하지 않는 이미지 파일 조회 시 404 NOT_FOUND를 반환한다")
    fun getImage_notFound_returns404() {
        val nonExistentFileName = UUID.randomUUID().toString() + ".jpg"

        mockMvc
            .perform(get("/api/v1/space-images/{fileName}", nonExistentFileName))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"))
    }

    @Test
    @DisplayName("경로 탈출 시도 파일명 요청 시 404 NOT_FOUND를 반환한다")
    fun getImage_pathTraversal_returns404() {
        mockMvc
            .perform(get("/api/v1/space-images/{fileName}", "malicious-name.txt"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"))
    }

    @Test
    @DisplayName("비인가 사용자의 비-GET 메서드(POST, DELETE 등)는 거부(401 Unauthorized)된다")
    fun nonGetMethods_unauthenticated_rejected() {
        mockMvc
            .perform(post("/api/v1/space-images/test.jpg"))
            .andExpect(status().isUnauthorized)

        mockMvc
            .perform(delete("/api/v1/space-images/test.jpg"))
            .andExpect(status().isUnauthorized)
    }
}
