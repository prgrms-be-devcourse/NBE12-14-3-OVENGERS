package com.ovengers.slotkey.stats.controller

import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.stats.dto.response.PublicLocationRankingResponse
import com.ovengers.slotkey.stats.dto.response.PublicSpaceRankingResponse
import com.ovengers.slotkey.stats.entity.RankingMetric
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.service.SpaceRankingQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 공개 랭킹 API. 비로그인 방문자도 볼 수 있고(SecurityConfig GET /api/v1/stats 하위 permitAll), 같은 확정 스냅샷을 쓰되
 * 순매출은 내려주지 않으며 NET_REVENUE 정렬도 거절한다(정렬 순서로 매출 순위가 드러나지 않게). 명세: api-spec §14-2.
 */
@Tag(name = "통계", description = "베스트 공간 랭킹(매일 00:01 확정값) 조회 API. 순매출은 제외")
@RestController
@RequestMapping("/api/v1/stats")
class StatsController(
    private val queryService: SpaceRankingQueryService,
) {
    @Operation(
        summary = "공간별 베스트 랭킹(공개)",
        description = "정렬 기준은 RESERVATION_COUNT(기본) 또는 SLOT_COUNT. 응답에 순매출이 없다.",
    )
    @GetMapping("/space-ranking")
    fun getSpaceRanking(
        @Parameter(description = "DAILY | WEEKLY | MONTHLY (필수)")
        @RequestParam(name = "period", required = false)
        period: RankingPeriodType?,
        @Parameter(description = "조회할 날짜(yyyy-MM-dd). 그 날짜를 포함하는 달력 기간(KST, 주는 월요일 시작) (필수)")
        @RequestParam(name = "date", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate?,
        @Parameter(description = "정렬 기준. RESERVATION_COUNT(기본) | SLOT_COUNT")
        @RequestParam(name = "metric", required = false)
        metric: RankingMetric?,
        @Parameter(description = "지역 필터: 판교 | 강남 | 하남")
        @RequestParam(name = "location", required = false)
        location: String?,
        @Parameter(description = "최대 개수. 기본 10, 최대 50")
        @RequestParam(name = "limit", defaultValue = "10")
        limit: Int,
    ): ResponseEntity<ApiResponse<PublicSpaceRankingResponse>> {
        requirePeriodAndDate(period, date)
        return ResponseEntity.ok(
            ApiResponse.success(
                PublicSpaceRankingResponse.from(
                    queryService.getSpaceRanking(period, date, publicMetric(metric), location, limit),
                ),
            ),
        )
    }

    @Operation(summary = "지역별 베스트 랭킹(공개)", description = "공간 랭킹과 같은 규칙. 응답에 순매출이 없다.")
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
    ): ResponseEntity<ApiResponse<PublicLocationRankingResponse>> {
        requirePeriodAndDate(period, date)
        return ResponseEntity.ok(
            ApiResponse.success(
                PublicLocationRankingResponse.from(
                    queryService.getLocationRanking(period, date, publicMetric(metric), limit),
                ),
            ),
        )
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

    private fun publicMetric(metric: RankingMetric?): RankingMetric {
        if (metric == null) {
            return RankingMetric.RESERVATION_COUNT
        }
        if (metric == RankingMetric.NET_REVENUE) {
            throw BusinessException(ErrorCode.VALIDATION_FAILED, "NET_REVENUE 정렬은 지원하지 않습니다.")
        }
        return metric
    }
}
