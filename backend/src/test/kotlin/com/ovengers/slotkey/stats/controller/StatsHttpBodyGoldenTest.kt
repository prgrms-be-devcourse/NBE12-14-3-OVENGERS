package com.ovengers.slotkey.stats.controller

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
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.stats.dto.response.SpaceRankingItem
import com.ovengers.slotkey.stats.dto.response.SpaceRankingResponse
import com.ovengers.slotkey.stats.entity.RankingMetric
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.RankingStatus
import com.ovengers.slotkey.stats.service.ReservationStatusCountService
import com.ovengers.slotkey.stats.service.SpaceRankingQueryService
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * #192 통계 도메인 대표 HTTP 본문 골든.
 *
 * 실제 Spring MVC 슬라이스(운영 ObjectMapper·GlobalExceptionHandler·SecurityConfig)를 사용한다.
 * 랭킹 서비스는 대역이므로 스냅샷 조회·기간 경계 계산은 검증하지 않는다(SpaceRankingQueryServiceIntegrationTest가 담당).
 * 정적 골든은 전환 전 Java 커밋 a380133에서 동일 입력/직렬화 경로로 캡처했다.
 */
@WebMvcTest(StatsController::class, AdminStatsController::class)
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
class StatsHttpBodyGoldenTest {
    private companion object {
        const val GOLDEN_DIRECTORY = "/golden/stats/"
        val DATE: LocalDate = LocalDate.of(2026, 10, 14)
    }

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @MockBean
    private lateinit var spaceRankingQueryService: SpaceRankingQueryService

    @MockBean
    private lateinit var reservationStatusCountService: ReservationStatusCountService

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
    @DisplayName("GET /api/v1/stats/space-ranking 공개 응답은 netRevenue 없이 Java 기준 전체 HTTP 본문을 반환한다")
    fun publicSpaceRankingHidesRevenueAndMatchesJavaGolden() {
        given(
            spaceRankingQueryService.getSpaceRanking(
                RankingPeriodType.DAILY,
                DATE,
                RankingMetric.RESERVATION_COUNT,
                null,
                10,
            ),
        ).willReturn(ranking(RankingMetric.RESERVATION_COUNT))

        val body =
            mvc
                .perform(
                    get("/api/v1/stats/space-ranking")
                        .param("period", "DAILY")
                        .param("date", "2026-10-14"),
                ).andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsByteArray

        val actual = mapper.readTree(body)

        assertThat(actual.at("/data/items/0").has("netRevenue")).isFalse()
        assertThat(actual.at("/data/items/1").has("netRevenue")).isFalse()
        assertThat(actual.at("/data/metric").asText()).isEqualTo("RESERVATION_COUNT")
        assertThat(actual).isEqualTo(readGolden("public-space-ranking-http-success.json"))
    }

    @Test
    @DisplayName("공개 순위는 정렬 기준 NET_REVENUE 요청을 400 VALIDATION_FAILED 정적 오류 본문으로 거절하고 서비스를 호출하지 않는다")
    fun publicRankingRejectsNetRevenueMetric() {
        val result =
            mvc
                .perform(
                    get("/api/v1/stats/space-ranking")
                        .param("period", "DAILY")
                        .param("date", "2026-10-14")
                        .param("metric", "NET_REVENUE"),
                ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("NET_REVENUE 정렬은 지원하지 않습니다."))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn()

        assertThat(mapper.readTree(result.response.contentAsByteArray))
            .isEqualTo(readGolden("public-space-ranking-net-revenue-error.json"))
        verifyNoInteractions(spaceRankingQueryService)
    }

    @Test
    @DisplayName("GET /api/v1/admin/stats/space-ranking 관리자 응답은 netRevenue를 포함한 Java 기준 전체 HTTP 본문을 반환한다")
    fun adminSpaceRankingMatchesJavaGolden() {
        given(
            spaceRankingQueryService.getSpaceRanking(
                RankingPeriodType.DAILY,
                DATE,
                RankingMetric.NET_REVENUE,
                null,
                10,
            ),
        ).willReturn(ranking(RankingMetric.NET_REVENUE))

        val body =
            mvc
                .perform(
                    get("/api/v1/admin/stats/space-ranking")
                        .header("Authorization", adminAuthorization())
                        .param("period", "DAILY")
                        .param("date", "2026-10-14")
                        .contentType(MediaType.APPLICATION_JSON),
                ).andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsByteArray

        val actual = mapper.readTree(body)

        assertThat(actual.at("/data/items/0/netRevenue").asLong()).isEqualTo(125_000L)
        assertThat(actual).isEqualTo(readGolden("admin-space-ranking-http-success.json"))
    }

    @Test
    @DisplayName("period가 없는 관리자 랭킹 요청은 400 VALIDATION_FAILED 정적 오류 본문과 필수 안내 메시지를 반환한다")
    fun adminRankingMissingPeriodRejected() {
        val result =
            mvc
                .perform(
                    get("/api/v1/admin/stats/space-ranking")
                        .header("Authorization", adminAuthorization())
                        .param("date", "2026-10-14"),
                ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("period는 필수입니다."))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn()

        assertThat(mapper.readTree(result.response.contentAsByteArray))
            .isEqualTo(readGolden("admin-space-ranking-missing-period-error.json"))
        verifyNoInteractions(spaceRankingQueryService)
    }

    private fun ranking(metric: RankingMetric): SpaceRankingResponse =
        SpaceRankingResponse(
            RankingPeriodType.DAILY,
            LocalDate.of(2026, 10, 13),
            LocalDate.of(2026, 10, 14),
            metric,
            RankingStatus.CONFIRMED,
            null,
            LocalDateTime.of(2026, 10, 14, 0, 1),
            listOf(
                SpaceRankingItem(1, 2, 11L, "판교 A룸", "판교", SpaceStatus.ACTIVE, 125_000L, 10, 20),
                SpaceRankingItem(2, null, 12L, "강남 B룸", "강남", SpaceStatus.INACTIVE, 90_000L, 8, 16),
            ),
        )

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
}
