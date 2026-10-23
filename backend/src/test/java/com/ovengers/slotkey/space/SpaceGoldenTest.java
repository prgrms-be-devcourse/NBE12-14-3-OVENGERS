package com.ovengers.slotkey.space;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.space.dto.response.SlotResponse;
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse;
import com.ovengers.slotkey.space.dto.response.SpaceResponse;
import com.ovengers.slotkey.space.dto.response.SpaceSlotAvailabilityResponse;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kotlin 전환 전 공간 응답 DTO의 JSON 계약을 정적 골든으로 고정한다.
 * 기준 JSON은 Java 실행에서 생성한 뒤 검토해 저장했으며, 테스트가 매번 덮어쓰지 않는다.
 */
@JsonTest
class SpaceGoldenTest {

    private static final String GOLDEN_DIRECTORY = "/golden/space/";
    private static final LocalTime OPENING_TIME = LocalTime.of(9, 0);
    private static final LocalTime CLOSING_TIME = LocalTime.of(18, 0);

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("SpaceResponse는 목록 응답의 숫자·HH:mm·enum·이미지 경로 계약을 유지한다")
    void spaceResponseWithImage() throws Exception {
        Space space = space("판교 A룸", "자연광이 좋은 회의실",
                "/api/v1/space-images/11111111-1111-1111-1111-111111111111.png");

        assertMatchesGolden(SpaceResponse.from(space), "space-response-with-image.json");
    }

    @Test
    @DisplayName("SpaceResponse는 description 필드가 없고 imagePath null을 그대로 직렬화한다")
    void spaceResponseWithNullImagePath() throws Exception {
        Space space = space("판교 B룸", null, null);

        JsonNode actual = serialize(SpaceResponse.from(space));

        assertThat(actual.has("description")).isFalse();
        assertThat(actual.get("imagePath").isNull()).isTrue();
        assertMatchesGolden(actual, "space-response-with-null-image-path.json");
    }

    @Test
    @DisplayName("SpaceDetailResponse는 설명·이미지·HH:mm·version을 모두 유지한다")
    void spaceDetailResponse() throws Exception {
        Space space = space("판교 A룸", "자연광이 좋은 회의실",
                "/api/v1/space-images/11111111-1111-1111-1111-111111111111.png");

        assertMatchesGolden(SpaceDetailResponse.from(space), "space-detail-response.json");
    }

    @Test
    @DisplayName("SpaceDetailResponse의 null description/imagePath는 필드 누락이 아니라 null로 유지된다")
    void spaceDetailResponseWithNullFields() throws Exception {
        Space space = space("판교 B룸", null, null);

        JsonNode actual = serialize(SpaceDetailResponse.from(space));

        assertThat(actual.get("description").isNull()).isTrue();
        assertThat(actual.get("imagePath").isNull()).isTrue();
        assertMatchesGolden(actual, "space-detail-response-with-null-description-and-image-path.json");
    }

    @Test
    @DisplayName("SpaceSlotAvailabilityResponse와 SlotResponse는 슬롯 시각·isAvailable 필드명·값을 유지한다")
    void slotAvailability() throws Exception {
        List<SlotResponse> slots = List.of(
                SlotResponse.of(
                        LocalDateTime.of(2026, 10, 14, 9, 0),
                        LocalDateTime.of(2026, 10, 14, 9, 30),
                        false),
                SlotResponse.of(
                        LocalDateTime.of(2026, 10, 14, 9, 30),
                        LocalDateTime.of(2026, 10, 14, 10, 0),
                        true)
        );

        JsonNode actual = serialize(
                SpaceSlotAvailabilityResponse.of(11L, LocalDate.of(2026, 10, 14), slots));

        assertThat(actual.at("/slots/0/slotStart").asText()).isEqualTo("2026-10-14T09:00:00");
        assertThat(actual.at("/slots/0/slotEnd").asText()).isEqualTo("2026-10-14T09:30:00");
        assertThat(actual.at("/slots/0/isAvailable").asBoolean()).isFalse();
        assertThat(actual.at("/slots/1/isAvailable").asBoolean()).isTrue();
        assertMatchesGolden(actual, "space-slot-availability.json");
    }

    @Test
    @DisplayName("빈 슬롯 목록은 빈 배열로 직렬화한다")
    void emptySlotAvailability() throws Exception {
        JsonNode actual = serialize(
                SpaceSlotAvailabilityResponse.of(11L, LocalDate.of(2026, 10, 14), List.of()));

        assertThat(actual.get("slots").isArray()).isTrue();
        assertThat(actual.get("slots")).isEmpty();
        assertMatchesGolden(actual, "space-slot-availability-empty.json");
    }

    private JsonNode serialize(Object value) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(value));
    }

    private void assertMatchesGolden(Object value, String goldenFile) throws Exception {
        assertMatchesGolden(serialize(value), goldenFile);
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

    private static Space space(String name, String description, String imagePath) {
        return Space.builder()
                .id(11L)
                .name(name)
                .location("판교")
                .description(description)
                .capacity(6)
                .pricePerSlot(5_000L)
                .imagePath(imagePath)
                .openingTime(OPENING_TIME)
                .closingTime(CLOSING_TIME)
                .status(SpaceStatus.ACTIVE)
                .version(3)
                .build();
    }
}
