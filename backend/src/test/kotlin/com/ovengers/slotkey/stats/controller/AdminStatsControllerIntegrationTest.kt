package com.ovengers.slotkey.stats.controller

import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.SpaceRankingSnapshot
import com.ovengers.slotkey.stats.repository.SpaceRankingSnapshotRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import com.ovengers.slotkey.support.SettableClockConfig
import com.ovengers.slotkey.support.SettableClockConfig.SettableClock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** api-spec §14 의 공간·지역 랭킹 조회 API(단계 5)를 HTTP 수준에서 확인한다. 기준 시각은 2026-10-16(금) 10:00 KST. */
@AutoConfigureMockMvc
@Transactional
@Import(SettableClockConfig::class)
class AdminStatsControllerIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var snapshotRepository: SpaceRankingSnapshotRepository

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var settableClock: SettableClock

    private lateinit var adminToken: String
    private lateinit var adminMember: Member

    @BeforeEach
    fun setUp() {
        settableClock.set(ZonedDateTime.of(2026, 10, 16, 10, 0, 0, 0, SettableClockConfig.KST))
        adminMember = saveMember(MemberRole.ADMIN)
        adminToken = bearer(adminMember)
    }

    // ---------- 인증·인가 ----------

    @Test
    @DisplayName("미인증 요청은 두 랭킹 API 모두 401 AUTHENTICATION_REQUIRED이다")
    fun unauthenticated() {
        mockMvc
            .perform(get(SPACE_RANKING).param("period", "DAILY").param("date", "2026-10-14"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        mockMvc
            .perform(get(LOCATION_RANKING).param("period", "DAILY").param("date", "2026-10-14"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("USER 역할은 두 랭킹 API 모두 403 ACCESS_DENIED이다")
    fun userRoleForbidden() {
        val userToken = bearer(saveMember(MemberRole.USER))

        mockMvc
            .perform(
                get(SPACE_RANKING)
                    .header(HttpHeaders.AUTHORIZATION, userToken)
                    .param("period", "DAILY")
                    .param("date", "2026-10-14"),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        mockMvc
            .perform(
                get(LOCATION_RANKING)
                    .header(HttpHeaders.AUTHORIZATION, userToken)
                    .param("period", "DAILY")
                    .param("date", "2026-10-14"),
            ).andExpect(status().isForbidden)
    }

    // ---------- 응답 모양 ----------

    @Test
    @DisplayName("공간 랭킹은 api-spec §14의 최상위 필드와 items 필드를 그대로 내려준다")
    fun spaceRankingResponseShape() {
        val a = saveSpace("판교 A룸", "판교")
        snap(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 13), a, "판교", 100L, 1, 1)
        snap(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 14), a, "판교", 5000L, 3, 6)

        spaceRanking("period", "DAILY", "date", "2026-10-14")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.period").value("DAILY"))
            .andExpect(jsonPath("$.data.periodStart").value("2026-10-14"))
            .andExpect(jsonPath("$.data.periodEnd").value("2026-10-15"))
            .andExpect(jsonPath("$.data.metric").value("NET_REVENUE"))
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.computedAt").value("2026-10-15T00:01:00"))
            .andExpect(jsonPath("$.data.confirmAt").doesNotExist())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andExpect(jsonPath("$.data.items[0].previousRank").value(1))
            .andExpect(jsonPath("$.data.items[0].spaceId").value(a))
            .andExpect(jsonPath("$.data.items[0].spaceName").value("판교 A룸"))
            .andExpect(jsonPath("$.data.items[0].location").value("판교"))
            .andExpect(jsonPath("$.data.items[0].spaceStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.data.items[0].netRevenue").value(5000))
            .andExpect(jsonPath("$.data.items[0].reservationCount").value(3))
            .andExpect(jsonPath("$.data.items[0].slotCount").value(6))
    }

    @Test
    @DisplayName("지역 랭킹은 api-spec §14의 items 필드(location, spaceCount, 세 지표)를 내려준다")
    fun locationRankingResponseShape() {
        val a = saveSpace("A", "판교")
        val b = saveSpace("B", "판교")
        snap(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 14), a, "판교", 100L, 1, 2)
        snap(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 14), b, "판교", 200L, 2, 3)

        mockMvc
            .perform(
                get(LOCATION_RANKING)
                    .header(HttpHeaders.AUTHORIZATION, adminToken)
                    .param("period", "DAILY")
                    .param("date", "2026-10-14")
                    .param("metric", "SLOT_COUNT"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.metric").value("SLOT_COUNT"))
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andExpect(jsonPath("$.data.items[0].location").value("판교"))
            .andExpect(jsonPath("$.data.items[0].spaceCount").value(2))
            .andExpect(jsonPath("$.data.items[0].netRevenue").value(300))
            .andExpect(jsonPath("$.data.items[0].reservationCount").value(3))
            .andExpect(jsonPath("$.data.items[0].slotCount").value(5))
    }

    @Test
    @DisplayName("진행 중인 기간은 200 PENDING과 confirmAt을 내려주고 items는 비어 있다")
    fun pendingResponse() {
        spaceRanking("period", "DAILY", "date", "2026-10-16")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.confirmAt").value("2026-10-17T00:00:00"))
            .andExpect(jsonPath("$.data.computedAt").doesNotExist())
            .andExpect(jsonPath("$.data.items.length()").value(0))
    }

    @Test
    @DisplayName("어제 일간(end == today)은 확정 행이 있으면 CONFIRMED, 00:00~00:01에 행이 없으면 PENDING이다")
    fun yesterdayDaily() {
        val a = saveSpace("판교 A룸", "판교")
        snap(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 15), a, "판교", 100L, 1, 1)
        snap(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 16), a, "판교", 5000L, 3, 6)

        // 통계 기준 시각을 다음 날로 옮긴 뒤, 만료되지 않는 세션을 다시 발급해 통계 응답을 검증한다.
        settableClock.set(ZonedDateTime.of(2026, 10, 17, 9, 59, 0, 0, SettableClockConfig.KST))
        adminToken = bearer(adminMember)
        spaceRanking("period", "DAILY", "date", "2026-10-16")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].netRevenue").value(5000))

        snapshotRepository.deleteByPeriod(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 16))
        settableClock.set(ZonedDateTime.of(2026, 10, 17, 0, 0, 30, 0, SettableClockConfig.KST))
        spaceRanking("period", "DAILY", "date", "2026-10-16")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.confirmAt").value("2026-10-17T00:00:00"))
    }

    @Test
    @DisplayName("확정 기록이 시작되기 전의 기간은 200 UNAVAILABLE이다")
    fun unavailableResponse() {
        spaceRanking("period", "MONTHLY", "date", "2026-08-15")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.data.items.length()").value(0))
    }

    @Test
    @DisplayName("metric·location·limit 파라미터를 받는다")
    fun acceptsOptionalParameters() {
        val p1 = saveSpace("P1", "판교")
        val p2 = saveSpace("P2", "판교")
        val g = saveSpace("G", "강남")
        snap(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 14), p1, "판교", 100L, 9, 1)
        snap(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 14), p2, "판교", 900L, 1, 1)
        snap(RankingPeriodType.DAILY, LocalDate.of(2026, 10, 14), g, "강남", 999L, 99, 1)

        spaceRanking(
            "period",
            "DAILY",
            "date",
            "2026-10-14",
            "metric",
            "RESERVATION_COUNT",
            "location",
            "판교",
            "limit",
            "1",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.metric").value("RESERVATION_COUNT"))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].spaceId").value(p1))
    }

    // ---------- 400 ----------

    @ParameterizedTest
    @ValueSource(
        strings = [
            "date=2026-10-14", // period 누락
            "period=DAILY", // date 누락
            "period=HOURLY&date=2026-10-14", // 없는 period
            "period=daily&date=2026-10-14", // 소문자
            "period=DAILY&date=2026-10-14&metric=PROFIT", // 없는 metric
            "period=DAILY&date=2026/10/14", // 날짜 형식
            "period=DAILY&date=2026-13-40", // 없는 날짜
            "period=DAILY&date=2026-10-17", // 현재 기간보다 미래(일간)
            "period=WEEKLY&date=2026-10-19", // 미래(주간)
            "period=MONTHLY&date=2026-11-01", // 미래(월간)
            "period=DAILY&date=2026-10-14&limit=0",
            "period=DAILY&date=2026-10-14&limit=51",
            "period=DAILY&date=2026-10-14&limit=abc",
            "period=DAILY&date=2026-10-14&location=서울",
        ],
    )
    @DisplayName("잘못된 파라미터는 공간 랭킹에서 400 VALIDATION_FAILED이다")
    fun invalidSpaceRankingParameters(query: String) {
        mockMvc
            .perform(get("$SPACE_RANKING?$query").header(HttpHeaders.AUTHORIZATION, adminToken))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "date=2026-10-14",
            "period=DAILY",
            "period=DAILY&date=2026-10-14&metric=PROFIT",
            "period=DAILY&date=2026-10-17",
            "period=DAILY&date=2026-10-14&limit=51",
        ],
    )
    @DisplayName("잘못된 파라미터는 지역 랭킹에서도 400 VALIDATION_FAILED이다")
    fun invalidLocationRankingParameters(query: String) {
        mockMvc
            .perform(get("$LOCATION_RANKING?$query").header(HttpHeaders.AUTHORIZATION, adminToken))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    // ---------- 상태별 건수 ----------

    @Test
    @DisplayName("status-counts는 date와 일곱 상태를 0 포함으로 내려준다")
    fun statusCountsShape() {
        mockMvc
            .perform(get(STATUS_COUNTS).header(HttpHeaders.AUTHORIZATION, adminToken).param("date", "2026-10-14"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.date").value("2026-10-14"))
            .andExpect(jsonPath("$.data.counts.HELD").value(0))
            .andExpect(jsonPath("$.data.counts.EXPIRED").value(0))
            .andExpect(jsonPath("$.data.counts.CONFIRMED").value(0))
            .andExpect(jsonPath("$.data.counts.IN_USE").value(0))
            .andExpect(jsonPath("$.data.counts.COMPLETED").value(0))
            .andExpect(jsonPath("$.data.counts.CANCELLED").value(0))
            .andExpect(jsonPath("$.data.counts.NO_SHOW").value(0))
    }

    @Test
    @DisplayName("status-counts는 date를 생략하면 오늘(KST)이다")
    fun statusCountsDefaultsToToday() {
        mockMvc
            .perform(get(STATUS_COUNTS).header(HttpHeaders.AUTHORIZATION, adminToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.date").value("2026-10-16"))
    }

    @Test
    @DisplayName("status-counts는 미인증 401, USER 403이다")
    fun statusCountsAuthorization() {
        mockMvc.perform(get(STATUS_COUNTS)).andExpect(status().isUnauthorized)
        mockMvc
            .perform(get(STATUS_COUNTS).header(HttpHeaders.AUTHORIZATION, bearer(saveMember(MemberRole.USER))))
            .andExpect(status().isForbidden)
    }

    @ParameterizedTest
    @ValueSource(strings = ["date=2026/10/14", "date=abc", "date=2026-13-40", "spaceId=abc", "spaceId=0", "spaceId=-1"])
    @DisplayName("status-counts의 잘못된 파라미터는 400 VALIDATION_FAILED이다")
    fun statusCountsInvalidParameters(query: String) {
        mockMvc
            .perform(get("$STATUS_COUNTS?$query").header(HttpHeaders.AUTHORIZATION, adminToken))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    // ---------- helpers ----------

    private fun spaceRanking(vararg keyValues: String): ResultActions {
        var request = get(SPACE_RANKING).header(HttpHeaders.AUTHORIZATION, adminToken)
        var i = 0
        while (i < keyValues.size) {
            request = request.param(keyValues[i], keyValues[i + 1])
            i += 2
        }
        return mockMvc.perform(request)
    }

    private fun saveMember(role: MemberRole): Member {
        val member = Member(role.name.lowercase() + "-" + System.nanoTime() + "@test.com", "stats-test")
        ReflectionTestUtils.setField(member, "role", role)
        ReflectionTestUtils.setField(member, "status", MemberStatus.ACTIVE)
        return memberRepository.saveAndFlush(member)
    }

    private fun bearer(member: Member): String = "Bearer " + issueSessionBoundAccessToken(member, jwtProvider)

    private fun saveSpace(
        name: String,
        location: String,
    ): Long {
        val space =
            spaceRepository.saveAndFlush(
                Space
                    .builder()
                    .name(name)
                    .location(location)
                    .capacity(4)
                    .pricePerSlot(5000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(22, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )
        return requireNotNull(space.id)
    }

    private fun snap(
        type: RankingPeriodType,
        start: LocalDate,
        spaceId: Long,
        location: String,
        revenue: Long,
        count: Int,
        slots: Int,
    ) {
        snapshotRepository.saveAndFlush(
            SpaceRankingSnapshot(
                type,
                start,
                spaceId,
                location,
                revenue,
                count,
                slots,
                type.endOf(start).atTime(0, 1),
            ),
        )
    }

    companion object {
        private const val SPACE_RANKING = "/api/v1/admin/stats/space-ranking"
        private const val LOCATION_RANKING = "/api/v1/admin/stats/location-ranking"
        private const val STATUS_COUNTS = "/api/v1/admin/stats/status-counts"
    }
}
