package com.ovengers.slotkey.space.controller

import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.common.response.PageResponse
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.space.authorization.SpaceAuthorizationService
import com.ovengers.slotkey.space.dto.request.SpaceCreateRequest
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.service.AdminSpaceService
import com.ovengers.slotkey.space.service.SpaceImageService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "관리자 - 공간", description = "관리자 공간 조회, 등록 및 수정 API")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses(
    SwaggerApiResponse(
        responseCode = "401",
        description = "인증 정보가 없거나 액세스 토큰이 유효하지 않음",
        content = [Content()],
    ),
    SwaggerApiResponse(
        responseCode = "403",
        description = "관리자 권한이 없음",
        content = [Content()],
    ),
)
@RestController
@RequestMapping("/api/v1/admin")
class AdminSpaceController(
    private val adminSpaceService: AdminSpaceService,
    private val spaceAuthorizationService: SpaceAuthorizationService,
    private val spaceImageService: SpaceImageService,
) {
    @Operation(
        summary = "관리자 공간 목록 조회",
        description = """
            공간 상태와 검색어로 공간 목록을 조회합니다.
            page는 0부터 시작하며, 기본 페이지 크기는 20입니다.
            """,
    )
    @ApiResponses(
        SwaggerApiResponse(
            responseCode = "200",
            description = "공간 목록 조회 성공",
            useReturnTypeSchema = true,
        ),
    )
    @GetMapping("/spaces")
    fun getSpaces(
        @Parameter(description = "공간 상태 필터. 생략하면 상태로 제한하지 않음")
        @RequestParam(name = "status", required = false)
        status: SpaceStatus?,
        @Parameter(description = "공간 검색어", example = "판교")
        @RequestParam(name = "keyword", required = false)
        keyword: String?,
        @ParameterObject
        @PageableDefault(size = 20)
        pageable: Pageable,
    ): ResponseEntity<ApiResponse<PageResponse<SpaceDetailResponse>>> {
        val response = PageResponse.from(adminSpaceService.getSpaces(status, keyword, pageable))
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(
        summary = "관리자 공간 상세 조회",
        description = "관리자가 수정할 공간의 상세 정보를 조회합니다.",
    )
    @ApiResponses(
        SwaggerApiResponse(
            responseCode = "200",
            description = "공간 상세 조회 성공",
            useReturnTypeSchema = true,
        ),
        SwaggerApiResponse(
            responseCode = "404",
            description = "SPACE_NOT_FOUND: 공간이 존재하지 않음",
            content = [Content()],
        ),
    )
    @GetMapping("/spaces/{spaceId}")
    fun getSpaceDetailById(
        @PathVariable("spaceId") spaceId: Long,
    ): ResponseEntity<ApiResponse<SpaceDetailResponse>> {
        val response = SpaceDetailResponse.from(adminSpaceService.getSpaceDetailById(spaceId))
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(
        summary = "공간 등록",
        description = """
            새로운 공간을 등록합니다.
            슬롯당 요금은 100원 이상이며 100원 단위여야 합니다.
            운영 종료 시각은 운영 시작 시각보다 늦어야 합니다.
            공간 대표 사진은 imagePath로 직접 입력하지 않고, 공간 등록 성공 후 반환된 data.id(spaceId)를 사용하여 별도 사진 업로드 API(PUT /api/v1/admin/spaces/{spaceId}/image)로 등록합니다. imagePath는 비워두어야 합니다.
            """,
    )
    @ApiResponses(
        SwaggerApiResponse(
            responseCode = "201",
            description = "공간 등록 성공",
            useReturnTypeSchema = true,
        ),
        SwaggerApiResponse(
            responseCode = "400",
            description = """
                VALIDATION_FAILED: 입력값 검증 실패.
                INVALID_PRICE_UNIT: 요금 단위 오류.
                INVALID_OPERATING_HOURS: 운영시간 오류.
                """,
            content = [Content()],
        ),
    )
    @PostMapping("/spaces")
    fun createSpace(
        @Valid @RequestBody
        request: SpaceCreateRequest,
        @AuthenticationPrincipal authPrincipal: AuthPrincipal?,
    ): ResponseEntity<ApiResponse<SpaceDetailResponse>> {
        spaceAuthorizationService.validateCanManageSpace(authPrincipal)
        val response = adminSpaceService.createSpace(request, authPrincipal!!.memberId)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response))
    }

    @Operation(
        summary = "공간 수정",
        description = """
            경로의 spaceId에 해당하는 공간 정보를 수정합니다.
            변경할 항목만 전달하며, null인 항목은 기존 값을 유지합니다.
            기존 예약과 충돌하는 운영시간 변경은 거절됩니다.
            공간 대표 사진 수정 및 교체는 imagePath로 전달하지 않고, 별도 사진 업로드 API(PUT /api/v1/admin/spaces/{spaceId}/image)를 사용합니다. imagePath는 비워두어야 합니다.
            """,
    )
    @ApiResponses(
        SwaggerApiResponse(
            responseCode = "200",
            description = "공간 수정 성공",
            useReturnTypeSchema = true,
        ),
        SwaggerApiResponse(
            responseCode = "400",
            description = "입력값 검증 실패 또는 요금·운영시간 오류",
            content = [Content()],
        ),
        SwaggerApiResponse(
            responseCode = "404",
            description = "SPACE_NOT_FOUND: 공간이 존재하지 않음",
            content = [Content()],
        ),
        SwaggerApiResponse(
            responseCode = "409",
            description = "SPACE_OPERATING_HOURS_CONFLICT: 변경할 운영시간이 기존 예약과 충돌함",
            content = [Content()],
        ),
    )
    @PatchMapping("/spaces/{spaceId}")
    fun updateSpace(
        @Parameter(description = "대상 공간 ID", example = "1")
        @PathVariable("spaceId")
        spaceId: Long?,
        @Valid @RequestBody
        request: SpaceUpdateRequest,
        @AuthenticationPrincipal authPrincipal: AuthPrincipal?,
    ): ResponseEntity<ApiResponse<SpaceDetailResponse>> {
        spaceAuthorizationService.validateCanManageSpace(authPrincipal)
        val response = adminSpaceService.updateSpace(spaceId, request, authPrincipal!!.memberId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(
        summary = "공간 대표 이미지 업로드 및 교체",
        description = """
            공간의 대표 이미지를 등록하거나 교체합니다.
            공간 JSON 등록(POST) 또는 수정(PATCH) 이후 반환된 spaceId와 multipart/form-data의 file 파트를 전송합니다.
            관리자 토큰(Bearer)이 필요합니다.
            지원 형식은 JPEG(image/jpeg) 및 PNG(image/png)이며, 최대 5MB, 최대 해상도는 4096x4096px입니다.
            업로드 성공 시 data.imagePath에 공개 이미지 조회 경로(/api/v1/space-images/{fileName})가 반환됩니다.
            사진 업로드만 실패한 경우 공간 자체의 등록·수정은 유지되므로, 동일한 spaceId로 본 API만 재시도할 수 있습니다.
            """,
    )
    @ApiResponses(
        SwaggerApiResponse(
            responseCode = "200",
            description = "공간 이미지 업로드 성공. data.imagePath에 접근 경로가 반환됩니다.",
            useReturnTypeSchema = true,
        ),
        SwaggerApiResponse(
            responseCode = "400",
            description = """
                IMAGE_FILE_EMPTY: 업로드할 이미지 파일이 비어 있음.
                INVALID_IMAGE_FORMAT: 지원하지 않는 이미지 형식 (JPEG, PNG만 지원).
                IMAGE_DIMENSIONS_EXCEEDED: 이미지 해상도가 4096x4096px를 초과함.
                """,
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ApiResponse::class),
                ),
            ],
        ),
        SwaggerApiResponse(
            responseCode = "404",
            description = "SPACE_NOT_FOUND: 공간이 존재하지 않음",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ApiResponse::class),
                ),
            ],
        ),
        SwaggerApiResponse(
            responseCode = "413",
            description = "IMAGE_SIZE_EXCEEDED: 파일 크기가 5MB를 초과함",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ApiResponse::class),
                ),
            ],
        ),
        SwaggerApiResponse(
            responseCode = "500",
            description = "IMAGE_STORAGE_ERROR: 이미지 파일 저장 실패",
            content = [
                Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ApiResponse::class),
                ),
            ],
        ),
    )
    @PutMapping(
        value = ["/spaces/{spaceId}/image"],
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun uploadSpaceImage(
        @Parameter(description = "대상 공간 ID", example = "1")
        @PathVariable("spaceId")
        spaceId: Long,
        @Parameter(description = "업로드할 공간 대표 사진 파일 (JPEG/PNG, 최대 5MB, 최대 4096x4096px)")
        @RequestParam("file")
        file: MultipartFile,
        @AuthenticationPrincipal authPrincipal: AuthPrincipal?,
    ): ResponseEntity<ApiResponse<SpaceDetailResponse>> {
        spaceAuthorizationService.validateCanManageSpace(authPrincipal)
        val response = spaceImageService.uploadAndAttachSpaceImage(spaceId, file, authPrincipal!!.memberId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}
