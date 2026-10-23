package com.ovengers.slotkey.space

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.space.dto.response.SlotResponse
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse
import com.ovengers.slotkey.space.dto.response.SpaceResponse
import com.ovengers.slotkey.space.dto.response.SpaceSlotAvailabilityResponse
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Objects

/**
 * Kotlin 전환 전 공간 응답 DTO의 JSON 계약을 정적 골든으로 고정한다.
 * 기준 JSON은 Java 실행에서 생성한 뒤 검토해 저장했으며, 테스트가 매번 덮어쓰지 않는다.
 */
@JsonTest
class SpaceGoldenTest {
    private companion object {
        const val GOLDEN_DIRECTORY = "/golden/space/"
    }

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val openingTime: LocalTime = LocalTime.of(9, 0)
    private val closingTime: LocalTime = LocalTime.of(18, 0)

    @Test
    @DisplayName("SpaceResponse는 목록 응답의 숫자·HH:mm·enum·이미지 경로 계약을 유지한다")
    fun spaceResponseWithImage() {
        val space =
            space(
                "판교 A룸",
                "자연광이 좋은 회의실",
                "/api/v1/space-images/11111111-1111-1111-1111-111111111111.png",
            )

        assertMatchesGolden(SpaceResponse.from(space), "space-response-with-image.json")
    }

    @Test
    @DisplayName("SpaceResponse는 description 필드가 없고 imagePath null을 그대로 직렬화한다")
    fun spaceResponseWithNullImagePath() {
        val space = space("판교 B룸", null, null)

        val actual = serialize(SpaceResponse.from(space))

        assertThat(actual.has("description")).isFalse()
        assertThat(actual.get("imagePath").isNull()).isTrue()
        assertMatchesGolden(actual, "space-response-with-null-image-path.json")
    }

    @Test
    @DisplayName("SpaceDetailResponse는 설명·이미지·HH:mm·version을 모두 유지한다")
    fun spaceDetailResponse() {
        val space =
            space(
                "판교 A룸",
                "자연광이 좋은 회의실",
                "/api/v1/space-images/11111111-1111-1111-1111-111111111111.png",
            )

        assertMatchesGolden(SpaceDetailResponse.from(space), "space-detail-response.json")
    }

    @Test
    @DisplayName("SpaceDetailResponse의 null description/imagePath는 필드 누락이 아니라 null로 유지된다")
    fun spaceDetailResponseWithNullFields() {
        val space = space("판교 B룸", null, null)

        val actual = serialize(SpaceDetailResponse.from(space))

        assertThat(actual.get("description").isNull()).isTrue()
        assertThat(actual.get("imagePath").isNull()).isTrue()
        assertMatchesGolden(actual, "space-detail-response-with-null-description-and-image-path.json")
    }

    @Test
    @DisplayName("SpaceSlotAvailabilityResponse와 SlotResponse는 슬롯 시각·isAvailable 필드명·값을 유지한다")
    fun slotAvailability() {
        val slots =
            listOf(
                SlotResponse.of(
                    LocalDateTime.of(2026, 10, 14, 9, 0),
                    LocalDateTime.of(2026, 10, 14, 9, 30),
                    false,
                ),
                SlotResponse.of(
                    LocalDateTime.of(2026, 10, 14, 9, 30),
                    LocalDateTime.of(2026, 10, 14, 10, 0),
                    true,
                ),
            )

        val actual =
            serialize(
                SpaceSlotAvailabilityResponse.of(11L, LocalDate.of(2026, 10, 14), slots),
            )

        assertThat(actual.at("/slots/0/slotStart").asText()).isEqualTo("2026-10-14T09:00:00")
        assertThat(actual.at("/slots/0/slotEnd").asText()).isEqualTo("2026-10-14T09:30:00")
        assertThat(actual.at("/slots/0/isAvailable").asBoolean()).isFalse()
        assertThat(actual.at("/slots/1/isAvailable").asBoolean()).isTrue()
        assertMatchesGolden(actual, "space-slot-availability.json")
    }

    @Test
    @DisplayName("빈 슬롯 목록은 빈 배열로 직렬화한다")
    fun emptySlotAvailability() {
        val actual =
            serialize(
                SpaceSlotAvailabilityResponse.of(11L, LocalDate.of(2026, 10, 14), emptyList()),
            )

        assertThat(actual.get("slots").isArray).isTrue()
        assertThat(actual.get("slots")).isEmpty()
        assertMatchesGolden(actual, "space-slot-availability-empty.json")
    }

    private fun serialize(value: Any): JsonNode = objectMapper.readTree(objectMapper.writeValueAsString(value))

    private fun assertMatchesGolden(
        value: Any,
        goldenFile: String,
    ) = assertMatchesGolden(serialize(value), goldenFile)

    private fun assertMatchesGolden(
        actual: JsonNode,
        goldenFile: String,
    ) {
        Objects
            .requireNonNull(
                javaClass.getResourceAsStream(GOLDEN_DIRECTORY + goldenFile),
                "골든 파일이 존재해야 한다: $goldenFile",
            ).use { source ->
                val expected = objectMapper.readTree(source)
                assertThat(actual)
                    .`as`("전환 전 Java 응답과 골든 JSON이 같아야 한다: %s", goldenFile)
                    .isEqualTo(expected)
            }
    }

    private fun space(
        name: String,
        description: String?,
        imagePath: String?,
    ): Space =
        Space
            .builder()
            .id(11L)
            .name(name)
            .location("판교")
            .description(description)
            .capacity(6)
            .pricePerSlot(5_000L)
            .imagePath(imagePath)
            .openingTime(openingTime)
            .closingTime(closingTime)
            .status(SpaceStatus.ACTIVE)
            .version(3)
            .build()
}
