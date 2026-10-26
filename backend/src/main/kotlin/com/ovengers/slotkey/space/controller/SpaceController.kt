package com.ovengers.slotkey.space.controller

import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.common.response.PageResponse
import com.ovengers.slotkey.space.dto.request.SpaceSearchCondition
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse
import com.ovengers.slotkey.space.dto.response.SpaceResponse
import com.ovengers.slotkey.space.dto.response.SpaceSlotAvailabilityResponse
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.service.SpaceQueryService
import com.ovengers.slotkey.space.service.SpaceSlotAvailabilityService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalTime
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@Tag(name = "공간", description = "비회원도 이용할 수 있는 공간 및 슬롯 조회 API")
@RestController
@RequestMapping("/api/v1")
class SpaceController(
    private val spaceQueryService: SpaceQueryService,
    private val slotAvailabilityService: SpaceSlotAvailabilityService,
) {
    @Operation(
        summary = "공간 목록 조회",
        description = """
            공간 목록을 페이지 단위로 조회합니다.
            검색어(이름·위치·설명), 지역, 최소·최대 가격(0원 이상, 최소 요금 <= 최대 요금)으로 필터링할 수 있습니다.
            날짜와 시작·종료 시간을 모두 지정하면 영업시간 내에 있으면서 해당 시간대에 예약 점유가 없는 공간을 조회합니다.
            시작·종료 시간은 30분 단위(초/나노초 0)여야 하며 시작 시각은 종료 시각보다 빨라야 합니다.
            조회 이후 예약 가능 여부는 달라질 수 있으며, 실제 예약 생성 시 다시 확인합니다.
            page는 0부터 시작하며, 기본 페이지 크기는 20입니다.
            """,
    )
    @ApiResponses(
        SwaggerApiResponse(
            responseCode = "200",
            description = "공간 목록 조회 성공",
            useReturnTypeSchema = true,
        ),
        SwaggerApiResponse(
            responseCode = "400",
            description = "잘못된 검색 조건 (음수 요금, 요금 역전, 부분 시간 입력, 30분 단위 위반, 시간 역전 등)",
            useReturnTypeSchema = false,
        ),
    )
    @GetMapping("/spaces")
    fun getSpacesPage(
        @ParameterObject
        @PageableDefault(size = 20, direction = Sort.Direction.DESC)
        pageable: Pageable,
        @Parameter(description = "공간 검색어 (이름, 위치, 설명 대상)", example = "미팅룸")
        @RequestParam(name = "keyword", required = false)
        keyword: String?,
        @Parameter(description = "지역", example = "판교")
        @RequestParam(name = "location", required = false)
        location: String?,
        @Parameter(description = "최소 가격 (0 이상)", example = "0")
        @RequestParam(name = "minPrice", required = false)
        minPrice: Long?,
        @Parameter(description = "최대 가격 (0 이상)", example = "10000")
        @RequestParam(name = "maxPrice", required = false)
        maxPrice: Long?,
        @Parameter(description = "예약 가능 여부를 조회할 날짜", example = "2026-10-01")
        @RequestParam(name = "date", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate?,
        @Parameter(description = "조회할 시작 시간 (30분 단위)", example = "09:00:00")
        @RequestParam(name = "startTime", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
        startTime: LocalTime?,
        @Parameter(description = "조회할 종료 시간 (30분 단위)", example = "10:00:00")
        @RequestParam(name = "endTime", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
        endTime: LocalTime?,
    ): ResponseEntity<ApiResponse<PageResponse<SpaceResponse>>> {
        val condition =
            SpaceSearchCondition(
                keyword,
                location,
                minPrice,
                maxPrice,
                date,
                startTime,
                endTime,
            )
        val spacePage: Page<Space> = spaceQueryService.getSpacesPage(pageable, condition)
        val responsePage: Page<SpaceResponse> = spacePage.map(SpaceResponse::from)
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(responsePage)))
    }

    @Operation(
        summary = "공간 상세 조회",
        description = "공간의 이름, 위치, 소개, 수용 인원, 요금, 운영시간 및 이미지 경로를 조회합니다.",
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
        @Parameter(description = "조회할 공간 ID", example = "1")
        @PathVariable("spaceId")
        spaceId: Long,
    ): ResponseEntity<ApiResponse<SpaceDetailResponse>> {
        val space = spaceQueryService.getSpaceDetailById(spaceId)
        return ResponseEntity.ok(ApiResponse.success(SpaceDetailResponse.from(space)))
    }

    @Operation(
        summary = "날짜별 예약 가능 슬롯 조회",
        description = """
            지정한 공간과 날짜의 30분 단위 슬롯 및 예약 가능 여부를 조회합니다.
            isAvailable이 true이면 조회 시점에 예약 가능한 슬롯입니다.
            조회 후 다른 사용자가 선점할 수 있으므로 실제 예약 가능 여부는 예약 생성 시 다시 확인합니다.
            """,
    )
    @ApiResponses(
        SwaggerApiResponse(
            responseCode = "200",
            description = "슬롯 조회 성공",
            useReturnTypeSchema = true,
        ),
        SwaggerApiResponse(
            responseCode = "400",
            description = "필수 날짜 누락 또는 날짜 형식 오류",
            content = [Content()],
        ),
        SwaggerApiResponse(
            responseCode = "404",
            description = "SPACE_NOT_FOUND: 공간이 존재하지 않음",
            content = [Content()],
        ),
    )
    @GetMapping("/spaces/{spaceId}/slots")
    fun getSlotAvailability(
        @Parameter(description = "조회할 공간 ID", example = "1")
        @PathVariable("spaceId")
        spaceId: Long,
        @Parameter(description = "조회 날짜 (yyyy-MM-dd)", example = "2026-10-01")
        @RequestParam(name = "date")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate,
    ): ResponseEntity<ApiResponse<SpaceSlotAvailabilityResponse>> {
        val response = slotAvailabilityService.getSlotAvailability(spaceId, date)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}
