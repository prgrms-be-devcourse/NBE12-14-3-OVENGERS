package com.ovengers.slotkey.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import com.ovengers.slotkey.stats.dto.response.LocationRankingItem;
import com.ovengers.slotkey.stats.dto.response.LocationRankingResponse;
import com.ovengers.slotkey.stats.dto.response.PublicLocationRankingResponse;
import com.ovengers.slotkey.stats.dto.response.PublicSpaceRankingResponse;
import com.ovengers.slotkey.stats.dto.response.SpaceRankingItem;
import com.ovengers.slotkey.stats.dto.response.SpaceRankingResponse;
import com.ovengers.slotkey.stats.dto.response.StatusCountsResponse;
import com.ovengers.slotkey.stats.entity.RankingMetric;
import com.ovengers.slotkey.stats.entity.RankingPeriodType;
import com.ovengers.slotkey.stats.entity.RankingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kotlin 전환 전 통계 응답 DTO의 관리자/일반 회원 노출 경계와 JSON 계약을 정적 골든으로 고정한다.
 */
@JsonTest
class StatsGoldenTest {

    private static final String GOLDEN_DIRECTORY = "/golden/stats/";
    private static final LocalDateTime COMPUTED_AT = LocalDateTime.of(2026, 10, 14, 0, 1);

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("관리자 지역 랭킹은 netRevenue·previousRank null 경계·computedAt을 유지한다")
    void locationRankingConfirmed() throws Exception {
        LocationRankingResponse response = sampleLocationRankingConfirmed();

        JsonNode actual = serialize(response);

        assertThat(actual.at("/items/0/netRevenue").asLong()).isEqualTo(125_000L);
        assertThat(actual.at("/items/1/previousRank").isNull()).isTrue();
        assertThat(actual.get("computedAt").asText()).isEqualTo("2026-10-14T00:01:00");
        assertThat(actual.get("confirmAt").isNull()).isTrue();
        assertMatchesGolden(actual, "location-ranking-confirmed.json");
    }

    @Test
    @DisplayName("PENDING 지역 랭킹은 confirmAt을 내려주고 computedAt은 null이며 items가 비어 있다")
    void locationRankingPending() throws Exception {
        LocationRankingResponse response = new LocationRankingResponse(
                RankingPeriodType.DAILY,
                LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 16),
                RankingMetric.NET_REVENUE,
                RankingStatus.PENDING,
                LocalDateTime.of(2026, 10, 16, 0, 0),
                null,
                List.of());

        JsonNode actual = serialize(response);

        assertThat(actual.get("computedAt").isNull()).isTrue();
        assertThat(actual.get("confirmAt").asText()).isEqualTo("2026-10-16T00:00:00");
        assertThat(actual.get("items")).isEmpty();
        assertMatchesGolden(actual, "location-ranking-pending.json");
    }

    @Test
    @DisplayName("일반 회원 지역 랭킹은 netRevenue를 노출하지 않고 from 변환 필드를 유지한다")
    void publicLocationRanking() throws Exception {
        PublicLocationRankingResponse response = PublicLocationRankingResponse.from(sampleLocationRankingConfirmed());

        JsonNode actual = serialize(response);

        assertThat(actual.at("/items/0").has("netRevenue")).isFalse();
        assertThat(actual.at("/items/0/location").asText()).isEqualTo("판교");
        assertMatchesGolden(actual, "public-location-ranking.json");
    }

    @Test
    @DisplayName("관리자 공간 랭킹은 netRevenue·spaceStatus·previousRank null 경계를 유지한다")
    void spaceRankingConfirmed() throws Exception {
        SpaceRankingResponse response = sampleSpaceRankingConfirmed();

        JsonNode actual = serialize(response);

        assertThat(actual.at("/items/0/netRevenue").asLong()).isEqualTo(125_000L);
        assertThat(actual.at("/items/0/spaceStatus").asText()).isEqualTo("ACTIVE");
        assertThat(actual.at("/items/1/previousRank").isNull()).isTrue();
        assertMatchesGolden(actual, "space-ranking-confirmed.json");
    }

    @Test
    @DisplayName("UNAVAILABLE 공간 랭킹은 시각 null과 빈 items를 유지한다")
    void spaceRankingUnavailable() throws Exception {
        SpaceRankingResponse response = new SpaceRankingResponse(
                RankingPeriodType.MONTHLY,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 1),
                RankingMetric.RESERVATION_COUNT,
                RankingStatus.UNAVAILABLE,
                null,
                null,
                List.of());

        JsonNode actual = serialize(response);

        assertThat(actual.get("confirmAt").isNull()).isTrue();
        assertThat(actual.get("computedAt").isNull()).isTrue();
        assertThat(actual.get("items")).isEmpty();
        assertMatchesGolden(actual, "space-ranking-unavailable.json");
    }

    @Test
    @DisplayName("일반 회원 공간 랭킹은 netRevenue를 노출하지 않고 from 변환 필드를 유지한다")
    void publicSpaceRanking() throws Exception {
        PublicSpaceRankingResponse response = PublicSpaceRankingResponse.from(sampleSpaceRankingConfirmed());

        JsonNode actual = serialize(response);

        assertThat(actual.at("/items/0").has("netRevenue")).isFalse();
        assertThat(actual.at("/items/0/spaceStatus").asText()).isEqualTo("ACTIVE");
        assertMatchesGolden(actual, "public-space-ranking.json");
    }

    @Test
    @DisplayName("StatusCountsResponse는 7개 ReservationStatus를 0 포함 Map으로 직렬화한다")
    void statusCountsZeroFilled() throws Exception {
        Map<ReservationStatus, Long> counts = new EnumMap<>(ReservationStatus.class);
        for (ReservationStatus status : ReservationStatus.values()) {
            counts.put(status, 0L);
        }
        StatusCountsResponse response = new StatusCountsResponse(LocalDate.of(2026, 10, 14), counts);

        JsonNode actual = serialize(response);

        assertThat(actual.at("/counts").size()).isEqualTo(7);
        assertThat(actual.at("/counts/HELD").asLong()).isZero();
        assertThat(actual.at("/counts/NO_SHOW").asLong()).isZero();
        assertMatchesGolden(actual, "status-counts.json");
    }

    private static LocationRankingResponse sampleLocationRankingConfirmed() {
        return new LocationRankingResponse(
                RankingPeriodType.DAILY,
                LocalDate.of(2026, 10, 13),
                LocalDate.of(2026, 10, 14),
                RankingMetric.NET_REVENUE,
                RankingStatus.CONFIRMED,
                null,
                COMPUTED_AT,
                List.of(
                        new LocationRankingItem(1, 2, "판교", 3, 125_000L, 10, 20),
                        new LocationRankingItem(2, null, "강남", 2, 90_000L, 8, 16)
                ));
    }

    private static SpaceRankingResponse sampleSpaceRankingConfirmed() {
        return new SpaceRankingResponse(
                RankingPeriodType.DAILY,
                LocalDate.of(2026, 10, 13),
                LocalDate.of(2026, 10, 14),
                RankingMetric.NET_REVENUE,
                RankingStatus.CONFIRMED,
                null,
                COMPUTED_AT,
                List.of(
                        new SpaceRankingItem(1, 2, 11L, "판교 A룸", "판교", SpaceStatus.ACTIVE, 125_000L, 10, 20),
                        new SpaceRankingItem(2, null, 12L, "강남 B룸", "강남", SpaceStatus.INACTIVE, 90_000L, 8, 16)
                ));
    }

    private JsonNode serialize(Object value) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(value));
    }

    private void assertMatchesGolden(JsonNode actual, String goldenFile) throws Exception {
        try (InputStream source = Objects.requireNonNull(
                getClass().getResourceAsStream(GOLDEN_DIRECTORY + goldenFile),
                "골든 파일이 존재해야 한다: " + goldenFile)) {
            JsonNode expected = objectMapper.readTree(source);
            assertThat(actual)
                    .as("전환 전 Java 응답과 골든 JSON이 같아야 한다: %s", goldenFile)
                    .isEqualTo(expected);
        }
    }
}
