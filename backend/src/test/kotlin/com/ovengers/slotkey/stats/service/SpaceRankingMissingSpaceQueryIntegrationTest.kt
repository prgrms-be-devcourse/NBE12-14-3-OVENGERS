package com.ovengers.slotkey.stats.service

import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.stats.entity.RankingMetric
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.RankingStatus
import com.ovengers.slotkey.stats.entity.SpaceRankingSnapshot
import com.ovengers.slotkey.stats.repository.SpaceRankingSnapshotRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import com.ovengers.slotkey.support.SettableClockConfig
import com.ovengers.slotkey.support.SettableClockConfig.SettableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 확정 스냅샷이 참조하는 공간이 삭제된 경우(#186)의 조회 정책.
 * 승인 정책: 누락 행은 spaceName `삭제된 공간`, spaceStatus INACTIVE로 표시하고
 * spaceId·rank·previousRank·지표·스냅샷 location·기간 정보를 그대로 보존한다.
 * 정렬·limit·직전 기간 순위·지역 집계는 누락 여부와 무관하게 전체 스냅샷 행 기준을 유지한다.
 */
@Import(SettableClockConfig::class)
class SpaceRankingMissingSpaceQueryIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var queryService: SpaceRankingQueryService

    @Autowired
    private lateinit var snapshotRepository: SpaceRankingSnapshotRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var settableClock: SettableClock

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val testPeriod = RankingPeriodType.DAILY
    private val testDate = LocalDate.of(2027, 3, 1)
    private val previousDate = LocalDate.of(2027, 2, 28)
    private val computedAt = LocalDateTime.of(2027, 3, 2, 0, 1, 0)

    @BeforeEach
    fun setUp() {
        // 확정 이후 시점으로 Clock 설정 (2027-03-02 00:02 KST)
        settableClock.set(ZonedDateTime.of(2027, 3, 2, 0, 2, 0, 0, SettableClockConfig.KST))
        cleanSnapshots()
        cleanFixtureSpaces()
    }

    @AfterEach
    fun tearDown() {
        cleanSnapshots()
        cleanFixtureSpaces()
    }

    @Test
    @DisplayName("참조 공간이 없는 스냅샷만 있으면 NPE 없이 삭제된 공간으로 표시하고 원본 spaceId·순위·지표·기간 정보를 보존한다")
    fun getSpaceRanking_whenOnlyReferencedSpaceMissing_returnsFallbackDisplay() {
        val missingSpaceId = 999_999_999L
        assertThat(spaceRepository.findById(missingSpaceId)).isEmpty

        // FK가 없는 독립 집계 테이블이므로 참조 공간이 없더라도 스냅샷 행이 정상 적재 가능함을 입증
        saveSnapshot(missingSpaceId, "서울 강남", 50000L, 5, 10)

        val response =
            queryService.getSpaceRanking(
                testPeriod,
                testDate,
                RankingMetric.NET_REVENUE,
                null,
                10,
            )

        assertThat(response.status).isEqualTo(RankingStatus.CONFIRMED)
        assertThat(response.computedAt).isEqualTo(computedAt)
        assertThat(response.periodStart).isEqualTo(testDate)
        assertThat(response.items).hasSize(1)
        val item = response.items[0]
        assertThat(item.spaceId).isEqualTo(missingSpaceId)
        assertThat(item.rank).isEqualTo(1)
        assertThat(item.previousRank).isNull()
        assertThat(item.spaceName).isEqualTo("삭제된 공간")
        assertThat(item.spaceStatus).isEqualTo(SpaceStatus.INACTIVE)
        assertThat(item.location).isEqualTo("서울 강남")
        assertThat(item.netRevenue).isEqualTo(50000L)
        assertThat(item.reservationCount).isEqualTo(5)
        assertThat(item.slotCount).isEqualTo(10)
    }

    @Test
    @DisplayName("대조군: 참조 공간이 실제 spaces 테이블에 온전히 존재하는 경우 기존 이름·상태와 지표를 그대로 반환한다")
    fun getSpaceRanking_whenReferencedSpaceExists_keepsExistingBehavior() {
        val validSpaceId = savePresentSpace("존재하는 공간 A")

        saveSnapshot(validSpaceId, "서울 강남", 100000L, 10, 20)

        val response =
            queryService.getSpaceRanking(
                testPeriod,
                testDate,
                RankingMetric.NET_REVENUE,
                null,
                10,
            )

        assertThat(response.items).hasSize(1)
        val item = response.items[0]
        assertThat(item.spaceId).isEqualTo(validSpaceId)
        assertThat(item.spaceName).isEqualTo("존재하는 공간 A")
        assertThat(item.spaceStatus).isEqualTo(SpaceStatus.ACTIVE)
        assertThat(item.location).isEqualTo("서울 강남")
        assertThat(item.netRevenue).isEqualTo(100000L)
        assertThat(item.reservationCount).isEqualTo(10)
        assertThat(item.slotCount).isEqualTo(20)
    }

    @Test
    @DisplayName("누락 공간과 존재 공간이 섞이면 누락 행을 빼지 않고 전체 스냅샷 순위 그대로 1·2위를 매긴다")
    fun getSpaceRanking_whenMixedMissingAndPresent_keepsFullRanking() {
        val presentSpaceId = savePresentSpace("존재하는 공간 B")
        val missingSpaceId = 999_999_998L
        saveSnapshot(presentSpaceId, "서울 강남", 300L, 3, 3)
        saveSnapshot(missingSpaceId, "서울 강남", 900L, 9, 9)

        val items = spaceRanking(null, 10).items

        assertThat(items.map { it.spaceId }).containsExactly(missingSpaceId, presentSpaceId)
        assertThat(items.map { it.rank }).containsExactly(1, 2)
        assertThat(items[0].spaceName).isEqualTo("삭제된 공간")
        assertThat(items[0].spaceStatus).isEqualTo(SpaceStatus.INACTIVE)
        assertThat(items[0].netRevenue).isEqualTo(900L)
        assertThat(items[1].spaceName).isEqualTo("존재하는 공간 B")
        assertThat(items[1].spaceStatus).isEqualTo(SpaceStatus.ACTIVE)
    }

    @Test
    @DisplayName("limit 밖에 있는 누락 공간은 상위 목록을 밀어내거나 뒤에서 채워지지 않는다")
    fun getSpaceRanking_whenMissingSpaceIsOutsideLimit_doesNotChangeTop() {
        val presentSpaceId = savePresentSpace("존재하는 공간 C")
        val topSpaceId = savePresentSpace("존재하는 공간 D")
        val missingSpaceId = 999_999_997L
        saveSnapshot(presentSpaceId, "서울 강남", 200L, 2, 2)
        saveSnapshot(missingSpaceId, "서울 강남", 100L, 1, 1)
        saveSnapshot(topSpaceId, "서울 강남", 800L, 8, 8)

        val top = spaceRanking(null, 2).items

        assertThat(top.map { it.spaceId }).containsExactly(topSpaceId, presentSpaceId)
        assertThat(top.map { it.rank }).containsExactly(1, 2)
        assertThat(top.map { it.spaceName }).containsExactly("존재하는 공간 D", "존재하는 공간 C")
    }

    @Test
    @DisplayName("previousRank는 직전 기간의 누락 공간도 포함한 전체 순위로 계산한다")
    fun getSpaceRanking_previousRankIncludesMissingReferences() {
        val presentSpaceId = savePresentSpace("존재하는 공간 E")
        val missingSpaceId = 999_999_996L
        saveSnapshot(presentSpaceId, "서울 강남", 100L, 1, 1, periodStart = previousDate)
        saveSnapshot(missingSpaceId, "서울 강남", 900L, 9, 9, periodStart = previousDate)
        saveSnapshot(presentSpaceId, "서울 강남", 500L, 5, 5)
        saveSnapshot(missingSpaceId, "서울 강남", 300L, 3, 3)

        val items = spaceRanking(null, 10).items

        assertThat(items.map { it.spaceId }).containsExactly(presentSpaceId, missingSpaceId)
        assertThat(items.map { it.previousRank }).containsExactly(2, 1)
        assertThat(items[1].spaceName).isEqualTo("삭제된 공간")
    }

    @Test
    @DisplayName("누락 공간도 location 필터와 지역 랭킹 집계에 스냅샷 행 그대로 포함된다")
    fun getSpaceRanking_missingSpaceIsIncludedInLocationFilterAndAggregation() {
        val presentSpaceId = savePresentSpace("존재하는 공간 F", "강남")
        val missingSpaceId = 999_999_995L
        saveSnapshot(presentSpaceId, "강남", 400L, 4, 8)
        saveSnapshot(missingSpaceId, "판교", 600L, 6, 12)

        val gangnam = spaceRanking("강남", 10).items
        val pangyo = spaceRanking("판교", 10).items
        val locations =
            queryService.getLocationRanking(
                testPeriod,
                testDate,
                RankingMetric.NET_REVENUE,
                10,
            )

        assertThat(gangnam.map { it.spaceId }).containsExactly(presentSpaceId)
        assertThat(pangyo).hasSize(1)
        assertThat(pangyo[0].spaceId).isEqualTo(missingSpaceId)
        assertThat(pangyo[0].spaceName).isEqualTo("삭제된 공간")
        assertThat(pangyo[0].rank).isEqualTo(1)
        assertThat(locations.items.map { it.location }).containsExactly("판교", "강남")
        assertThat(locations.items[0].spaceCount).isEqualTo(1)
        assertThat(locations.items[0].netRevenue).isEqualTo(600L)
        assertThat(locations.items[0].reservationCount).isEqualTo(6)
        assertThat(locations.items[0].slotCount).isEqualTo(12)
        assertThat(locations.items[0].previousRank).isNull()
    }

    // ---------- helpers ----------

    private fun spaceRanking(
        location: String?,
        limit: Int,
    ) = queryService.getSpaceRanking(testPeriod, testDate, RankingMetric.NET_REVENUE, location, limit)

    private fun savePresentSpace(
        name: String,
        location: String = "서울 강남",
    ): Long {
        val space =
            Space
                .builder()
                .name(name)
                .description("테스트 공간 설명")
                .location(location)
                .capacity(10)
                .pricePerSlot(5000L)
                .status(SpaceStatus.ACTIVE)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(22, 0))
                .build()
        return requireNotNull(spaceRepository.save(space).id)
    }

    private fun saveSnapshot(
        spaceId: Long,
        location: String,
        revenue: Long,
        count: Int,
        slots: Int,
        periodStart: LocalDate = testDate,
    ) {
        snapshotRepository.save(
            SpaceRankingSnapshot(
                testPeriod,
                periodStart,
                spaceId,
                location,
                revenue,
                count,
                slots,
                if (periodStart == testDate) computedAt else periodStart.plusDays(1).atTime(0, 1),
            ),
        )
    }

    private fun cleanSnapshots() {
        jdbcTemplate.update("DELETE FROM space_ranking_snapshot")
    }

    private fun cleanFixtureSpaces() {
        jdbcTemplate.update("DELETE FROM spaces WHERE name LIKE '존재하는 공간%'")
    }
}
