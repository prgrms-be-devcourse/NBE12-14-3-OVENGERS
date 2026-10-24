package com.ovengers.slotkey.space.controller

import com.ovengers.slotkey.support.IntegrationTestSupport
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class SpaceOpenApiDocumentationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @DisplayName("공간 이미지 관련 OpenAPI 3.0 명세가 계약에 맞게 생성된다")
    fun openApi_spaceImageEndpoints_meetContract() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            // 1. 관리자 공간 사진 업로드 PUT
            .andExpect(
                jsonPath("$.paths['/api/v1/admin/spaces/{spaceId}/image'].put")
                    .exists(),
            ).andExpect(
                jsonPath("$.paths['/api/v1/admin/spaces/{spaceId}/image'].put.summary")
                    .value("공간 대표 이미지 업로드 및 교체"),
            ).andExpect(
                jsonPath("$.paths['/api/v1/admin/spaces/{spaceId}/image'].put.security[0].bearerAuth")
                    .isArray,
            ).andExpect(
                jsonPath("$.paths['/api/v1/admin/spaces/{spaceId}/image'].put.parameters[?(@.name == 'spaceId')].required")
                    .value(true),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.requestBody.content['multipart/form-data'].schema.required",
                ).value(hasItem("file")),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.requestBody.content['multipart/form-data'].schema.properties.file.type",
                ).value("string"),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.requestBody.content['multipart/form-data'].schema.properties.file.format",
                ).value("binary"),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.responses.200.content['application/json'].schema.\$ref",
                ).value("#/components/schemas/ApiResponseSpaceDetailResponse"),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.responses.400.description",
                ).value(containsString("IMAGE_FILE_EMPTY")),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.responses.400.content['application/json'].schema.\$ref",
                ).value(containsString("ApiResponse")),
            ).andExpect(
                jsonPath("$.paths['/api/v1/admin/spaces/{spaceId}/image'].put.responses.401")
                    .exists(),
            ).andExpect(
                jsonPath("$.paths['/api/v1/admin/spaces/{spaceId}/image'].put.responses.403")
                    .exists(),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.responses.404.description",
                ).value(containsString("SPACE_NOT_FOUND")),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.responses.404.content['application/json'].schema.\$ref",
                ).value(containsString("ApiResponse")),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.responses.413.description",
                ).value(containsString("IMAGE_SIZE_EXCEEDED")),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.responses.413.content['application/json'].schema.\$ref",
                ).value(containsString("ApiResponse")),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.responses.500.description",
                ).value(containsString("IMAGE_STORAGE_ERROR")),
            ).andExpect(
                jsonPath(
                    "$.paths['/api/v1/admin/spaces/{spaceId}/image']" +
                        ".put.responses.500.content['application/json'].schema.\$ref",
                ).value(containsString("ApiResponse")),
            )
            // 2. 공개 공간 사진 조회 GET
            .andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get")
                    .exists(),
            ).andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get.summary")
                    .value("공간 이미지 조회 (공개)"),
            ).andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get.security")
                    .doesNotExist(),
            ).andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get.parameters[?(@.name == 'fileName')].required")
                    .value(true),
            ).andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get.parameters[?(@.name == 'fileName')].example")
                    .doesNotExist(),
            ).andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get.responses.200.headers['Content-Length']")
                    .exists(),
            ).andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get.responses.200.headers['Cache-Control']")
                    .exists(),
            ).andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get.responses.200.headers['X-Content-Type-Options']")
                    .exists(),
            ).andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get.responses.200.content['image/jpeg'].schema.format")
                    .value("binary"),
            ).andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get.responses.200.content['image/png'].schema.format")
                    .value("binary"),
            ).andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get.responses.404.description")
                    .value(containsString("IMAGE_NOT_FOUND")),
            ).andExpect(
                jsonPath("$.paths['/api/v1/space-images/{fileName}'].get.responses.404.content['application/json'].schema.\$ref")
                    .value(containsString("ApiResponse")),
            )
            // 3. 공간 등록(POST) 및 수정(PATCH)의 imagePath 안내 연계
            .andExpect(
                jsonPath("$.paths['/api/v1/admin/spaces'].post.description")
                    .value(containsString("PUT /api/v1/admin/spaces/{spaceId}/image")),
            ).andExpect(
                jsonPath("$.paths['/api/v1/admin/spaces/{spaceId}'].patch.description")
                    .value(containsString("PUT /api/v1/admin/spaces/{spaceId}/image")),
            )
            // 4. Request DTO 스키마 설명
            .andExpect(
                jsonPath("$.components.schemas.SpaceCreateRequest.properties.imagePath.description")
                    .value(containsString("VALIDATION_FAILED(400)")),
            ).andExpect(
                jsonPath("$.components.schemas.SpaceUpdateRequest.properties.imagePath.description")
                    .value(containsString("VALIDATION_FAILED(400)")),
            )
    }

    @Test
    @DisplayName("Swagger UI 엔드포인트에 비인가 사용자도 접근할 수 있다")
    fun swaggerUi_unauthenticated_accessible() {
        mockMvc
            .perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk)
    }
}
