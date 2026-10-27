package com.ovengers.slotkey.space.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.access.service.InactiveMemberAccessLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.config.SecurityConfig
import com.ovengers.slotkey.global.config.WebMvcConfig
import com.ovengers.slotkey.global.error.GlobalExceptionHandler
import com.ovengers.slotkey.global.security.CurrentMemberArgumentResolver
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.global.security.jwt.JwtUtil
import com.ovengers.slotkey.space.authorization.SpaceAuthorizationService
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.service.AdminSpaceService
import com.ovengers.slotkey.space.service.SpaceImageService
import com.ovengers.slotkey.space.service.SpaceQueryService
import com.ovengers.slotkey.space.service.SpaceSlotAvailabilityService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.MethodArgumentNotValidException
import java.time.LocalTime

/**
 * #192 공간 도메인 대표 HTTP 본문 골든.
 *
 * 실제 Spring MVC 슬라이스(운영 ObjectMapper·Bean Validation·GlobalExceptionHandler·SecurityConfig)를 사용한다.
 * 서비스는 대역이므로 DB·실제 JPA 영속성은 검증하지 않는다(공간 통합 테스트가 담당).
 * 정적 골든은 전환 전 Java 커밋 a380133에서 동일 입력/직렬화 경로로 캡처했다.
 */
@WebMvcTest(SpaceController::class, AdminSpaceController::class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import(
    SecurityConfig::class,
    ClockConfig::class,
    JwtProvider::class,
    WebMvcConfig::class,
    CurrentMemberArgumentResolver::class,
    GlobalExceptionHandler::class,
)
class SpaceHttpBodyGoldenTest {
    private companion object {
        const val GOLDEN_DIRECTORY = "/golden/space/"
    }

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @MockBean
    private lateinit var spaceQueryService: SpaceQueryService

    @MockBean
    private lateinit var slotAvailabilityService: SpaceSlotAvailabilityService

    @MockBean
    private lateinit var adminSpaceService: AdminSpaceService

    @MockBean
    private lateinit var spaceAuthorizationService: SpaceAuthorizationService

    @MockBean
    private lateinit var spaceImageService: SpaceImageService

    @MockBean
    private lateinit var sessions: AuthSessionService

    @MockBean
    private lateinit var inactiveAccess: InactiveMemberAccessLogService

    @Value("\${custom.jwt.secret-key}")
    private lateinit var secretKey: String

    @BeforeEach
    fun allowSession() {
        given(sessions.validate(anyString(), anyLong())).willReturn(AuthSessionService.ValidationResult.VALID)
    }

    @Test
    @DisplayName("GET /api/v1/spaces/{id} 정상 상세는 200 OK와 Java 기준 전체 HTTP 본문을 그대로 반환한다")
    fun spaceDetailHttpBodyMatchesJavaGolden() {
        val space =
            space(
                "판교 A룸",
                "자연광이 좋은 회의실",
                "/api/v1/space-images/11111111-1111-1111-1111-111111111111.png",
            )
        given(spaceQueryService.getSpaceDetailById(11L)).willReturn(space)

        val body =
            mvc
                .perform(get("/api/v1/spaces/{spaceId}", 11L))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsByteArray

        assertThat(mapper.readTree(body)).isEqualTo(readGolden("space-detail-http-success.json"))
    }

    @Test
    @DisplayName("선택 null(description·imagePath)을 포함한 상세도 null 필드를 누락 없이 그대로 직렬화한다")
    fun spaceDetailNullOptionalsMatchJavaGolden() {
        given(spaceQueryService.getSpaceDetailById(11L)).willReturn(space("판교 B룸", null, null))

        val body =
            mvc
                .perform(get("/api/v1/spaces/{spaceId}", 11L))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsByteArray

        val actual = mapper.readTree(body)
        assertThat(actual.at("/data/description").isNull).isTrue()
        assertThat(actual.at("/data/imagePath").isNull).isTrue()
        assertThat(actual.at("/data").has("description")).isTrue()
        assertThat(actual.at("/data").has("imagePath")).isTrue()
        assertThat(actual).isEqualTo(readGolden("space-detail-http-null-optionals.json"))
    }

    @Test
    @DisplayName("POST /api/v1/admin/spaces 대표 필수값(name 누락) 거절은 400 VALIDATION_FAILED와 서비스 미호출을 지킨다")
    fun createSpaceMissingNameRejectedBeforeService() {
        val requestBody =
            """
            {
              "location": "판교",
              "description": "설명",
              "capacity": 6,
              "pricePerSlot": 1000,
              "openingTime": "09:00",
              "closingTime": "18:00"
            }
            """.trimIndent()

        val result =
            mvc
                .perform(
                    post("/api/v1/admin/spaces")
                        .header("Authorization", adminAuthorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody),
                ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("must not be blank"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn()

        val exception = result.resolvedException
        assertThat(exception).isInstanceOf(MethodArgumentNotValidException::class.java)
        assertThat((exception as MethodArgumentNotValidException).bindingResult.getFieldError("name")).isNotNull()
        assertThat(mapper.readTree(result.response.contentAsByteArray))
            .isEqualTo(readGolden("space-create-missing-name-error.json"))
        verifyNoInteractions(adminSpaceService)
    }

    @Test
    @DisplayName("ADMIN이 아닌 익명 요청은 본문 검증 전에 401 AUTHENTICATION_REQUIRED로 차단된다")
    fun createSpaceAnonymousRejectedBySecurityFilter() {
        mvc
            .perform(
                post("/api/v1/admin/spaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))

        verifyNoInteractions(adminSpaceService)
    }

    private fun adminAuthorization(): String =
        "Bearer " +
            JwtUtil.createToken(
                secretKey,
                300_000L,
                mapOf(
                    "id" to 1L,
                    "email" to "admin@slotkey.test",
                    "role" to "ADMIN",
                    "sid" to "admin-session",
                ),
            )

    private fun readGolden(file: String): JsonNode {
        val stream =
            javaClass.getResourceAsStream(GOLDEN_DIRECTORY + file)
                ?: throw AssertionError("골든 파일이 존재해야 한다: $file")
        return stream.use { mapper.readTree(it) }
    }

    private fun space(
        name: String,
        description: String?,
        imagePath: String?,
    ): Space =
        Space
            .builder()
            .id(11L)
            .name(name)
            .location("판교")
            .description(description)
            .capacity(6)
            .pricePerSlot(5_000L)
            .imagePath(imagePath)
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(18, 0))
            .status(SpaceStatus.ACTIVE)
            .version(3)
            .build()
}
