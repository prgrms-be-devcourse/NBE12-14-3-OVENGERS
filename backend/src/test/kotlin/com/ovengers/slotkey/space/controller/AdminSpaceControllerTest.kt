package com.ovengers.slotkey.space.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.error.GlobalExceptionHandler
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.space.authorization.SpaceAuthorizationService
import com.ovengers.slotkey.space.dto.request.SpaceCreateRequest
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse
import com.ovengers.slotkey.space.dto.response.SpaceResponse
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.service.AdminSpaceService
import com.ovengers.slotkey.space.service.SpaceImageService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalTime

@ExtendWith(MockitoExtension::class)
class AdminSpaceControllerTest {
    private lateinit var mockMvc: MockMvc

    @Mock
    private lateinit var adminSpaceService: AdminSpaceService

    @Mock
    private lateinit var spaceAuthorizationService: SpaceAuthorizationService

    @Mock
    private lateinit var spaceImageService: SpaceImageService

    @InjectMocks
    private lateinit var adminSpaceController: AdminSpaceController

    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper()
        objectMapper.registerModule(JavaTimeModule())

        mockMvc =
            MockMvcBuilders
                .standaloneSetup(adminSpaceController)
                .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(GlobalExceptionHandler())
                .build()

        val authentication =
            UsernamePasswordAuthenticationToken(
                ADMIN_PRINCIPAL,
                null,
                ADMIN_PRINCIPAL.authorities,
            )
        SecurityContextHolder.getContext().authentication = authentication
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    @DisplayName("관리자 공간 상세 조회 요청은 경로의 spaceId로 200 OK와 ApiResponse를 반환한다")
    fun getSpaceDetailById_returnsOk() {
        // given
        val spaceId = 1L
        val space =
            Space
                .builder()
                .id(spaceId)
                .name("관리자 회의실")
                .location("서울시 강남구")
                .description("수정 화면 초기값")
                .capacity(8)
                .pricePerSlot(5000L)
                .imagePath("/images/admin-space.jpg")
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.INACTIVE)
                .version(2)
                .build()
        given(adminSpaceService.getSpaceDetailById(spaceId)).willReturn(space)

        // when & then
        mockMvc
            .perform(get("/api/v1/admin/spaces/{spaceId}", spaceId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.id").value(spaceId))
            .andExpect(jsonPath("$.data.name").value("관리자 회의실"))
            .andExpect(jsonPath("$.data.status").value("INACTIVE"))
            .andExpect(jsonPath("$.data.version").value(2))

        verify(adminSpaceService).getSpaceDetailById(spaceId)
    }

    @Test
    @DisplayName("존재하지 않는 관리자 공간 상세 조회는 404 SPACE_NOT_FOUND를 반환한다")
    fun getSpaceDetailById_notFound() {
        // given
        val spaceId = 999L
        given(adminSpaceService.getSpaceDetailById(spaceId))
            .willThrow(BusinessException(ErrorCode.SPACE_NOT_FOUND))

        // when & then
        mockMvc
            .perform(get("/api/v1/admin/spaces/{spaceId}", spaceId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"))

        verify(adminSpaceService).getSpaceDetailById(spaceId)
    }

    @Test
    @DisplayName("공간 등록 요청 시 201 Created와 ApiResponse 규격 응답을 반환한다")
    fun createSpace_returnsCreated() {
        // given
        val request =
            SpaceCreateRequest(
                "회의실 1",
                "강남",
                "깔끔한 회의실",
                6,
                5000L,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
            )

        val response =
            SpaceDetailResponse(
                1L,
                "회의실 1",
                "강남",
                request.description,
                6,
                5000L,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                SpaceStatus.ACTIVE,
                0,
            )

        given(adminSpaceService.createSpace(any(SpaceCreateRequest::class.java), eq(1L))).willReturn(response)

        // when & then
        mockMvc
            .perform(
                post("/api/v1/admin/spaces")
                    .header("X-Actor-Member-Id", 1L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.id").value(1L))
            .andExpect(jsonPath("$.data.name").value("회의실 1"))

        verify(spaceAuthorizationService).validateCanManageSpace(ADMIN_PRINCIPAL)
        verify(adminSpaceService).createSpace(any(SpaceCreateRequest::class.java), eq(ADMIN_PRINCIPAL.memberId))
    }

    @Test
    @DisplayName("필수 필드(이름 누락 등) 유효성 검증 실패 시 400 Bad Request를 반환한다")
    fun createSpace_validationFailed() {
        // name이 빈 문자열인 요청
        val invalidRequest =
            SpaceCreateRequest(
                "",
                "서울시 강남구",
                "설명",
                6,
                5000L,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
            )

        mockMvc
            .perform(
                post("/api/v1/admin/spaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(adminSpaceService)
    }

    @Test
    @DisplayName("공간 등록 요청 본문에 비어있지 않은 imagePath가 포함되면 400 Bad Request를 반환한다")
    fun createSpace_withNonEmptyImagePath_returnsBadRequest() {
        // imagePath가 비어있지 않은 요청
        val invalidRequest =
            SpaceCreateRequest(
                "회의실 1",
                "강남",
                "설명",
                6,
                5000L,
                "/malicious-path.jpg",
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
            )

        mockMvc
            .perform(
                post("/api/v1/admin/spaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(adminSpaceService)
    }

    @Test
    @DisplayName("요청 DTO의 blank 판정은 Java String.isBlank와 동일하게 NBSP를 공백으로 보지 않는다")
    fun requestBlankChecks_matchJavaStringIsBlank() {
        assertThat(createRequest(imagePath = "\u00a0").isImagePathEmpty()).isFalse()
        assertThat(createRequest(imagePath = "   ").isImagePathEmpty()).isTrue()
        assertThat(createRequest(imagePath = "").isImagePathEmpty()).isTrue()
        assertThat(createRequest(imagePath = null).isImagePathEmpty()).isTrue()
        assertThat(createRequest(location = "\u00a0").isLocationSupported()).isFalse()
        assertThat(createRequest(location = "강남").isLocationSupported()).isTrue()
        assertThat(createRequest(imagePath = "/images/space.jpg").isImagePathEmpty()).isFalse()

        val updateRequest =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                "\u00a0",
                null,
                null,
                null,
            )
        assertThat(updateRequest.isImagePathEmpty()).isFalse()
    }

    @Test
    @DisplayName("공간 등록 요청의 location이 NBSP이면 400 VALIDATION_FAILED를 반환한다")
    fun createSpace_withNbspLocation_returnsBadRequest() {
        val invalidRequest = createRequest(location = "\u00a0")

        mockMvc
            .perform(
                post("/api/v1/admin/spaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(adminSpaceService)
    }

    @Test
    @DisplayName("공간 등록 요청의 imagePath가 NBSP이면 400 VALIDATION_FAILED를 반환한다")
    fun createSpace_withNbspImagePath_returnsBadRequest() {
        val invalidRequest = createRequest(imagePath = "\u00a0")

        mockMvc
            .perform(
                post("/api/v1/admin/spaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(adminSpaceService)
    }

    @Test
    @DisplayName("공간 수정 요청 시 200 OK와 수정된 공간 상세를 반환한다")
    fun updateSpace_returnsOk() {
        // given
        val spaceId = 1L
        val request =
            SpaceUpdateRequest(
                null,
                "수정된 회의실",
                "하남",
                "업그레이드된 회의실",
                8,
                6000L,
                null,
                LocalTime.of(10, 0),
                LocalTime.of(20, 0),
                SpaceStatus.ACTIVE,
            )

        val response =
            SpaceDetailResponse(
                spaceId,
                "수정된 회의실",
                "하남",
                request.description,
                8,
                6000L,
                null,
                LocalTime.of(10, 0),
                LocalTime.of(20, 0),
                SpaceStatus.ACTIVE,
                1,
            )

        given(
            adminSpaceService.updateSpace(
                eq(spaceId),
                any(SpaceUpdateRequest::class.java),
                eq(ADMIN_PRINCIPAL.memberId),
            ),
        ).willReturn(response)

        // when & then
        mockMvc
            .perform(
                patch("/api/v1/admin/spaces/{spaceId}", spaceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.id").value(spaceId))
            .andExpect(jsonPath("$.data.name").value("수정된 회의실"))
            .andExpect(jsonPath("$.data.pricePerSlot").value(6000L))
            .andExpect(jsonPath("$.data.version").value(1))

        verify(spaceAuthorizationService).validateCanManageSpace(ADMIN_PRINCIPAL)
        verify(adminSpaceService).updateSpace(
            eq(spaceId),
            any(SpaceUpdateRequest::class.java),
            eq(ADMIN_PRINCIPAL.memberId),
        )
    }

    @Test
    @DisplayName("공간 수정 요청 본문에 비어있지 않은 imagePath가 포함되면 400 Bad Request를 반환한다")
    fun updateSpace_withNonEmptyImagePath_returnsBadRequest() {
        val spaceId = 1L
        val invalidRequest =
            SpaceUpdateRequest(
                null,
                "수정 회의실",
                "강남",
                "설명",
                6,
                5000L,
                "/arbitrary-path.jpg",
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                SpaceStatus.ACTIVE,
            )

        mockMvc
            .perform(
                patch("/api/v1/admin/spaces/{spaceId}", spaceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(adminSpaceService)
    }

    @Test
    @DisplayName("공간 수정 요청의 imagePath가 NBSP이면 400 VALIDATION_FAILED를 반환한다")
    fun updateSpace_withNbspImagePath_returnsBadRequest() {
        val invalidRequest =
            SpaceUpdateRequest(
                null,
                "수정 회의실",
                "강남",
                "설명",
                6,
                5000L,
                "\u00a0",
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                SpaceStatus.ACTIVE,
            )

        mockMvc
            .perform(
                patch("/api/v1/admin/spaces/{spaceId}", 1L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(adminSpaceService)
    }

    @Test
    @DisplayName("공간 등록 요청의 location이 판교·강남·하남이 아니면 400 VALIDATION_FAILED를 반환한다")
    fun createSpace_withUnsupportedLocation_returnsBadRequest() {
        val invalidRequest =
            SpaceCreateRequest(
                "회의실 1",
                "서울시 강남구",
                "설명",
                6,
                5000L,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
            )

        mockMvc
            .perform(
                post("/api/v1/admin/spaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(adminSpaceService)
    }

    @Test
    @DisplayName("공간 수정 요청의 location이 판교·강남·하남이 아니면 400 VALIDATION_FAILED를 반환한다")
    fun updateSpace_withUnsupportedLocation_returnsBadRequest() {
        val invalidRequest =
            SpaceUpdateRequest(
                null,
                null,
                "서울",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            )

        mockMvc
            .perform(
                patch("/api/v1/admin/spaces/{spaceId}", 1L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(adminSpaceService)
    }

    @Test
    @DisplayName("공간 수정 요청에서 location을 생략(null)하면 지역 검증을 통과한다")
    fun updateSpace_withoutLocation_passesValidation() {
        val request =
            SpaceUpdateRequest(
                null,
                "이름만 수정",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            )
        given(
            adminSpaceService.updateSpace(
                eq(1L),
                any(SpaceUpdateRequest::class.java),
                eq(ADMIN_PRINCIPAL.memberId),
            ),
        ).willReturn(
            SpaceDetailResponse(
                1L,
                "이름만 수정",
                "강남",
                null,
                6,
                5000L,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                SpaceStatus.ACTIVE,
                0,
            ),
        )

        mockMvc
            .perform(
                patch("/api/v1/admin/spaces/{spaceId}", 1L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk())
    }

    @Test
    @DisplayName("존재하지 않는 공간 수정 시 404 Not Found와 SPACE_NOT_FOUND 에러 코드를 반환한다")
    fun updateSpace_notFound() {
        // given
        val notFoundSpaceId = 999L
        val request =
            SpaceUpdateRequest(
                null,
                "수정 회의실",
                "판교",
                null,
                4,
                5000L,
                null,
                null,
                null,
                null,
            )

        given(
            adminSpaceService.updateSpace(
                eq(notFoundSpaceId),
                any(SpaceUpdateRequest::class.java),
                eq(ADMIN_PRINCIPAL.memberId),
            ),
        ).willThrow(BusinessException(ErrorCode.SPACE_NOT_FOUND))

        // when & then
        mockMvc
            .perform(
                patch("/api/v1/admin/spaces/{spaceId}", notFoundSpaceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"))
    }

    @Test
    @DisplayName("관리자 권한이 없는 사용자(USER)가 공간 등록 시 403 Forbidden을 반환한다")
    fun createSpace_accessDenied_forUser() {
        // given
        val authentication =
            UsernamePasswordAuthenticationToken(
                USER_PRINCIPAL,
                null,
                USER_PRINCIPAL.authorities,
            )
        SecurityContextHolder.getContext().authentication = authentication

        doThrow(BusinessException(ErrorCode.ACCESS_DENIED))
            .`when`(spaceAuthorizationService)
            .validateCanManageSpace(USER_PRINCIPAL)

        val request =
            SpaceCreateRequest(
                "회의실 1",
                "강남",
                "깔끔한 회의실",
                6,
                5000L,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
            )

        // when & then
        mockMvc
            .perform(
                post("/api/v1/admin/spaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        verifyNoInteractions(adminSpaceService)
    }

    @Test
    @DisplayName("인증 정보가 없는(null) 사용자가 공간 등록 시 403 Forbidden을 반환한다")
    fun createSpace_accessDenied_forNullPrincipal() {
        // given
        SecurityContextHolder.clearContext()

        doThrow(BusinessException(ErrorCode.ACCESS_DENIED))
            .`when`(spaceAuthorizationService)
            .validateCanManageSpace(null)

        val request =
            SpaceCreateRequest(
                "회의실 1",
                "강남",
                "깔끔한 회의실",
                6,
                5000L,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
            )

        // when & then
        mockMvc
            .perform(
                post("/api/v1/admin/spaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        verifyNoInteractions(adminSpaceService)
    }

    @Test
    @DisplayName("관리자 권한으로 사진 업로드 성공 시 200 OK와 갱신된 SpaceDetailResponse를 반환한다")
    fun uploadSpaceImage_success() {
        // given
        val spaceId = 1L
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3))

        val expectedResponse =
            SpaceDetailResponse(
                spaceId,
                "회의실 A",
                "강남",
                "설명",
                8,
                10000L,
                "/api/v1/space-images/abc-123.jpg",
                LocalTime.of(9, 0),
                LocalTime.of(22, 0),
                SpaceStatus.ACTIVE,
                0,
            )

        given(spaceImageService.uploadAndAttachSpaceImage(eq(spaceId), any(), eq(ADMIN_PRINCIPAL.memberId)))
            .willReturn(expectedResponse)

        // when & then
        mockMvc
            .perform(
                multipart(HttpMethod.PUT, "/api/v1/admin/spaces/{spaceId}/image", spaceId)
                    .file(file),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.id").value(spaceId))
            .andExpect(jsonPath("$.data.imagePath").value("/api/v1/space-images/abc-123.jpg"))

        verify(spaceAuthorizationService).validateCanManageSpace(ADMIN_PRINCIPAL)
        verify(spaceImageService).uploadAndAttachSpaceImage(eq(spaceId), any(), eq(ADMIN_PRINCIPAL.memberId))
    }

    @Test
    @DisplayName("일반 사용자가 사진 업로드 시 403 Forbidden을 반환한다")
    fun uploadSpaceImage_accessDenied_forUserRole() {
        // given
        val spaceId = 1L
        val authentication =
            UsernamePasswordAuthenticationToken(
                USER_PRINCIPAL,
                null,
                USER_PRINCIPAL.authorities,
            )
        SecurityContextHolder.getContext().authentication = authentication

        doThrow(BusinessException(ErrorCode.ACCESS_DENIED))
            .`when`(spaceAuthorizationService)
            .validateCanManageSpace(USER_PRINCIPAL)

        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3))

        // when & then
        mockMvc
            .perform(
                multipart(HttpMethod.PUT, "/api/v1/admin/spaces/{spaceId}/image", spaceId)
                    .file(file),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))

        verifyNoInteractions(spaceImageService)
    }

    @Test
    @DisplayName("존재하지 않는 공간에 사진 업로드 시 404 Not Found를 반환한다")
    fun uploadSpaceImage_spaceNotFound() {
        // given
        val nonExistentSpaceId = 999L
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3))

        doThrow(BusinessException(ErrorCode.SPACE_NOT_FOUND))
            .`when`(spaceImageService)
            .uploadAndAttachSpaceImage(eq(nonExistentSpaceId), any(), any())

        // when & then
        mockMvc
            .perform(
                multipart(HttpMethod.PUT, "/api/v1/admin/spaces/{spaceId}/image", nonExistentSpaceId)
                    .file(file),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"))
    }

    @Test
    @DisplayName("빈 파일 업로드 시 400 Bad Request와 IMAGE_FILE_EMPTY를 반환한다")
    fun uploadSpaceImage_emptyFile() {
        // given
        val spaceId = 1L
        val file = MockMultipartFile("file", "empty.jpg", "image/jpeg", byteArrayOf())

        doThrow(BusinessException(ErrorCode.IMAGE_FILE_EMPTY))
            .`when`(spaceImageService)
            .uploadAndAttachSpaceImage(eq(spaceId), any(), any())

        // when & then
        mockMvc
            .perform(
                multipart(HttpMethod.PUT, "/api/v1/admin/spaces/{spaceId}/image", spaceId)
                    .file(file),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("IMAGE_FILE_EMPTY"))
    }

    @Test
    @DisplayName("지원하지 않는 포맷이나 위조 서명 업로드 시 400 Bad Request와 INVALID_IMAGE_FORMAT을 반환한다")
    fun uploadSpaceImage_invalidFormat() {
        // given
        val spaceId = 1L
        val file = MockMultipartFile("file", "script.sh", "text/plain", byteArrayOf(1, 2, 3))

        doThrow(BusinessException(ErrorCode.INVALID_IMAGE_FORMAT))
            .`when`(spaceImageService)
            .uploadAndAttachSpaceImage(eq(spaceId), any(), any())

        // when & then
        mockMvc
            .perform(
                multipart(HttpMethod.PUT, "/api/v1/admin/spaces/{spaceId}/image", spaceId)
                    .file(file),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("INVALID_IMAGE_FORMAT"))
    }

    @Test
    @DisplayName("용량 초과 파일 업로드 시 413 Payload Too Large와 IMAGE_SIZE_EXCEEDED를 반환한다")
    fun uploadSpaceImage_sizeExceeded() {
        // given
        val spaceId = 1L
        val file = MockMultipartFile("file", "large.jpg", "image/jpeg", byteArrayOf(1, 2, 3))

        doThrow(BusinessException(ErrorCode.IMAGE_SIZE_EXCEEDED))
            .`when`(spaceImageService)
            .uploadAndAttachSpaceImage(eq(spaceId), any(), any())

        // when & then
        mockMvc
            .perform(
                multipart(HttpMethod.PUT, "/api/v1/admin/spaces/{spaceId}/image", spaceId)
                    .file(file),
            ).andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("IMAGE_SIZE_EXCEEDED"))
    }

    @Test
    @DisplayName("SpaceResponse.from과 SpaceDetailResponse.from은 Space 필드를 그대로 매핑한다")
    fun responseFactories_mapSpaceFields() {
        val space =
            Space
                .builder()
                .id(11L)
                .name("회의실")
                .location("강남")
                .description("설명")
                .capacity(6)
                .pricePerSlot(5000L)
                .imagePath("/images/space.jpg")
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(2)
                .build()

        assertThat(SpaceResponse.from(space)).isEqualTo(
            SpaceResponse(
                11L,
                "회의실",
                "강남",
                6,
                5000L,
                "/images/space.jpg",
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                SpaceStatus.ACTIVE,
            ),
        )
        assertThat(SpaceDetailResponse.from(space)).isEqualTo(
            SpaceDetailResponse(
                11L,
                "회의실",
                "강남",
                "설명",
                6,
                5000L,
                "/images/space.jpg",
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                SpaceStatus.ACTIVE,
                2,
            ),
        )

        val spaceWithoutOptionalFields =
            Space
                .builder()
                .id(12L)
                .name("회의실 2")
                .location("판교")
                .description(null)
                .capacity(4)
                .pricePerSlot(4000L)
                .imagePath(null)
                .openingTime(LocalTime.of(10, 0))
                .closingTime(LocalTime.of(19, 0))
                .status(SpaceStatus.INACTIVE)
                .version(3)
                .build()

        assertThat(SpaceResponse.from(spaceWithoutOptionalFields).imagePath).isNull()
        assertThat(SpaceDetailResponse.from(spaceWithoutOptionalFields).description).isNull()
        assertThat(SpaceDetailResponse.from(spaceWithoutOptionalFields).imagePath).isNull()
    }

    private fun createRequest(
        location: String? = "강남",
        imagePath: String? = null,
    ): SpaceCreateRequest =
        SpaceCreateRequest(
            "회의실 1",
            location,
            "설명",
            6,
            5000L,
            imagePath,
            LocalTime.of(9, 0),
            LocalTime.of(18, 0),
        )

    private companion object {
        val ADMIN_PRINCIPAL = AuthPrincipal(1L, "admin@test.com", MemberRole.ADMIN)
        val USER_PRINCIPAL = AuthPrincipal(2L, "user@test.com", MemberRole.USER)
    }
}
