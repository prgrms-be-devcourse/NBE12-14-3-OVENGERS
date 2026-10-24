package com.ovengers.slotkey.stats.controller

import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.stats.dto.response.LocationRankingResponse
import com.ovengers.slotkey.stats.dto.response.SpaceRankingResponse
import com.ovengers.slotkey.stats.dto.response.StatusCountsResponse
import com.ovengers.slotkey.stats.entity.RankingMetric
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.service.ReservationStatusCountService
import com.ovengers.slotkey.stats.service.SpaceRankingQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 관리자 통계 API. ADMIN 전용은 SecurityConfig의 /api/v1/admin 하위 경로 규칙이 보장한다. 명세: api-spec §14.
 */
@Tag(name = "관리자 - 통계", description = "베스트 공간 랭킹(매일 00:01 확정값) 조회 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/stats")
class AdminStatsController(
    private val queryService: SpaceRankingQueryService,
    private val statusCountService: ReservationStatusCountService,
) {
    @Operation(
        summary = "공간별 베스트 랭킹",
        description = "끝난 기간의 확정값만 순위를 내려준다. 진행 중인 기간은 status=PENDING, 확정 기록 이전은 UNAVAILABLE.",
    )
    @GetMapping("/space-ranking")
    fun getSpaceRanking(
        @Parameter(description = "DAILY | WEEKLY | MONTHLY (필수)")
        @RequestParam(name = "period", required = false)
        period: RankingPeriodType?,
        @Parameter(description = "조회할 날짜(yyyy-MM-dd). 그 날짜를 포함하는 달력 기간(KST, 주는 월요일 시작)을 조회한다 (필수)")
        @RequestParam(name = "date", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate?,
        @Parameter(description = "정렬 기준. 기본 NET_REVENUE")
        @RequestParam(name = "metric", required = false)
        metric: RankingMetric?,
        @Parameter(description = "지역 필터: 판교 | 강남 | 하남")
        @RequestParam(name = "location", required = false)
        location: String?,
        @Parameter(description = "최대 개수. 기본 10, 최대 50")
        @RequestParam(name = "limit", defaultValue = "10")
        limit: Int,
    ): ResponseEntity<ApiResponse<SpaceRankingResponse>> {
        requirePeriodAndDate(period, date)
        return ResponseEntity.ok(
            ApiResponse.success(
                queryService.getSpaceRanking(period, date, metricOrDefault(metric), location, limit),
            ),
        )
    }

    @Operation(summary = "지역별 베스트 랭킹", description = "공간 행을 스냅샷에 저장된 location으로 합산한다.")
    @GetMapping("/location-ranking")
    fun getLocationRanking(
        @RequestParam(name = "period", required = false)
        period: RankingPeriodType?,
        @RequestParam(name = "date", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate?,
        @RequestParam(name = "metric", required = false)
        metric: RankingMetric?,
        @RequestParam(name = "limit", defaultValue = "10")
        limit: Int,
    ): ResponseEntity<ApiResponse<LocationRankingResponse>> {
        requirePeriodAndDate(period, date)
        return ResponseEntity.ok(
            ApiResponse.success(
                queryService.getLocationRanking(period, date, metricOrDefault(metric), limit),
            ),
        )
    }

    @Operation(
        summary = "날짜별 예약 상태 건수",
        description = "해당 날짜(KST, 기본 오늘)에 start_time이 속한 예약의 상태별 건수. 실시간 값이며 일곱 상태를 0 포함으로 내려준다.",
    )
    @GetMapping("/status-counts")
    fun getStatusCounts(
        @Parameter(description = "조회 날짜(yyyy-MM-dd). 생략하면 오늘(KST)")
        @RequestParam(name = "date", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate?,
        @Parameter(description = "특정 공간만 센다(선택, 양수)")
        @RequestParam(name = "spaceId", required = false)
        spaceId: Long?,
    ): ResponseEntity<ApiResponse<StatusCountsResponse>> {
        val response = statusCountService.count(date, spaceId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    private fun requirePeriodAndDate(
        period: RankingPeriodType?,
        date: LocalDate?,
    ) {
        if (period == null) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "period는 필수입니다.")
        }
        if (date == null) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "date는 필수입니다.")
        }
    }

    private fun metricOrDefault(metric: RankingMetric?): RankingMetric = metric ?: RankingMetric.NET_REVENUE
}
