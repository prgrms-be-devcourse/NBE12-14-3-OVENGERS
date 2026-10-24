package com.ovengers.slotkey.stats

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.stats.dto.response.LocationRankingItem
import com.ovengers.slotkey.stats.dto.response.LocationRankingResponse
import com.ovengers.slotkey.stats.dto.response.PublicLocationRankingResponse
import com.ovengers.slotkey.stats.dto.response.PublicSpaceRankingResponse
import com.ovengers.slotkey.stats.dto.response.SpaceRankingItem
import com.ovengers.slotkey.stats.dto.response.SpaceRankingResponse
import com.ovengers.slotkey.stats.dto.response.StatusCountsResponse
import com.ovengers.slotkey.stats.entity.RankingMetric
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.RankingStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.EnumMap

/**
 * Kotlin 전환 전 통계 응답 DTO의 관리자/일반 회원 노출 경계와 JSON 계약을 정적 골든으로 고정한다.
 */
@JsonTest
class StatsGoldenTest {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("관리자 지역 랭킹은 netRevenue·previousRank null 경계·computedAt을 유지한다")
    fun locationRankingConfirmed() {
        val response = sampleLocationRankingConfirmed()

        val actual = serialize(response)

        assertThat(actual.at("/items/0/netRevenue").asLong()).isEqualTo(125_000L)
        assertThat(actual.at("/items/1/previousRank").isNull).isTrue()
        assertThat(actual.get("computedAt").asText()).isEqualTo("2026-10-14T00:01:00")
        assertThat(requireNotNull(actual.get("confirmAt")).isNull).isTrue()
        assertMatchesGolden(actual, "location-ranking-confirmed.json")
    }

    @Test
    @DisplayName("PENDING 지역 랭킹은 confirmAt을 내려주고 computedAt은 null이며 items가 비어 있다")
    fun locationRankingPending() {
        val response =
            LocationRankingResponse(
                RankingPeriodType.DAILY,
                LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 16),
                RankingMetric.NET_REVENUE,
                RankingStatus.PENDING,
                LocalDateTime.of(2026, 10, 16, 0, 0),
                null,
                emptyList(),
            )

        val actual = serialize(response)

        assertThat(requireNotNull(actual.get("computedAt")).isNull).isTrue()
        assertThat(actual.get("confirmAt").asText()).isEqualTo("2026-10-16T00:00:00")
        assertThat(actual.get("items")).isEmpty()
        assertMatchesGolden(actual, "location-ranking-pending.json")
    }

    @Test
    @DisplayName("일반 회원 지역 랭킹은 netRevenue를 노출하지 않고 from 변환 필드를 유지한다")
    fun publicLocationRanking() {
        val response = PublicLocationRankingResponse.from(sampleLocationRankingConfirmed())

        val actual = serialize(response)

        assertThat(actual.at("/items/0").has("netRevenue")).isFalse()
        assertThat(actual.at("/items/0/location").asText()).isEqualTo("판교")
        assertMatchesGolden(actual, "public-location-ranking.json")
    }

    @Test
    @DisplayName("관리자 공간 랭킹은 netRevenue·spaceStatus·previousRank null 경계를 유지한다")
    fun spaceRankingConfirmed() {
        val response = sampleSpaceRankingConfirmed()

        val actual = serialize(response)

        assertThat(actual.at("/items/0/netRevenue").asLong()).isEqualTo(125_000L)
        assertThat(actual.at("/items/0/spaceStatus").asText()).isEqualTo("ACTIVE")
        assertThat(actual.at("/items/1/previousRank").isNull).isTrue()
        assertMatchesGolden(actual, "space-ranking-confirmed.json")
    }

    @Test
    @DisplayName("UNAVAILABLE 공간 랭킹은 시각 null과 빈 items를 유지한다")
    fun spaceRankingUnavailable() {
        val response =
            SpaceRankingResponse(
                RankingPeriodType.MONTHLY,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 1),
                RankingMetric.RESERVATION_COUNT,
                RankingStatus.UNAVAILABLE,
                null,
                null,
                emptyList(),
            )

        val actual = serialize(response)

        assertThat(requireNotNull(actual.get("confirmAt")).isNull).isTrue()
        assertThat(requireNotNull(actual.get("computedAt")).isNull).isTrue()
        assertThat(actual.get("items")).isEmpty()
        assertMatchesGolden(actual, "space-ranking-unavailable.json")
    }

    @Test
    @DisplayName("일반 회원 공간 랭킹은 netRevenue를 노출하지 않고 from 변환 필드를 유지한다")
    fun publicSpaceRanking() {
        val response = PublicSpaceRankingResponse.from(sampleSpaceRankingConfirmed())

        val actual = serialize(response)

        assertThat(actual.at("/items/0").has("netRevenue")).isFalse()
        assertThat(actual.at("/items/0/spaceStatus").asText()).isEqualTo("ACTIVE")
        assertMatchesGolden(actual, "public-space-ranking.json")
    }

    @Test
    @DisplayName("StatusCountsResponse는 7개 ReservationStatus를 0 포함 Map으로 직렬화한다")
    fun statusCountsZeroFilled() {
        val counts = EnumMap<ReservationStatus, Long>(ReservationStatus::class.java)
        for (status in ReservationStatus.entries) {
            counts[status] = 0L
        }
        val response = StatusCountsResponse(LocalDate.of(2026, 10, 14), counts)

        val actual = serialize(response)

        assertThat(actual.at("/counts").size()).isEqualTo(7)
        assertThat(actual.at("/counts/HELD").asLong()).isZero()
        assertThat(actual.at("/counts/NO_SHOW").asLong()).isZero()
        assertMatchesGolden(actual, "status-counts.json")
    }

    private fun sampleLocationRankingConfirmed(): LocationRankingResponse =
        LocationRankingResponse(
            RankingPeriodType.DAILY,
            LocalDate.of(2026, 10, 13),
            LocalDate.of(2026, 10, 14),
            RankingMetric.NET_REVENUE,
            RankingStatus.CONFIRMED,
            null,
            COMPUTED_AT,
            listOf(
                LocationRankingItem(1, 2, "판교", 3, 125_000L, 10, 20),
                LocationRankingItem(2, null, "강남", 2, 90_000L, 8, 16),
            ),
        )

    private fun sampleSpaceRankingConfirmed(): SpaceRankingResponse =
        SpaceRankingResponse(
            RankingPeriodType.DAILY,
            LocalDate.of(2026, 10, 13),
            LocalDate.of(2026, 10, 14),
            RankingMetric.NET_REVENUE,
            RankingStatus.CONFIRMED,
            null,
            COMPUTED_AT,
            listOf(
                SpaceRankingItem(1, 2, 11L, "판교 A룸", "판교", SpaceStatus.ACTIVE, 125_000L, 10, 20),
                SpaceRankingItem(2, null, 12L, "강남 B룸", "강남", SpaceStatus.INACTIVE, 90_000L, 8, 16),
            ),
        )

    private fun serialize(value: Any): JsonNode = objectMapper.readTree(objectMapper.writeValueAsString(value))

    private fun assertMatchesGolden(
        actual: JsonNode,
        goldenFile: String,
    ) {
        val stream =
            javaClass.getResourceAsStream(GOLDEN_DIRECTORY + goldenFile)
                ?: throw AssertionError("골든 파일이 존재해야 한다: $goldenFile")
        stream.use { source ->
            val expected = objectMapper.readTree(source)
            assertThat(actual)
                .`as`("전환 전 Java 응답과 골든 JSON이 같아야 한다: %s", goldenFile)
                .isEqualTo(expected)
        }
    }

    companion object {
        private const val GOLDEN_DIRECTORY = "/golden/stats/"
        private val COMPUTED_AT: LocalDateTime = LocalDateTime.of(2026, 10, 14, 0, 1)
    }
}
