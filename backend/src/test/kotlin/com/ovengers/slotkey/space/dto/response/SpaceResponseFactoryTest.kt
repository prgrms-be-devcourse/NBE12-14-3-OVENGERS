package com.ovengers.slotkey.space.dto.response

import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalTime
import java.util.stream.Stream

class SpaceResponseFactoryTest {
    @Test
    @DisplayName("완전한 공간은 두 응답 팩토리에서 모든 필수 필드가 그대로 매핑된다")
    fun completeSpace_mapsAllFieldsInBothFactories() {
        // given
        val space = completeSpace()

        // when
        val listResponse = SpaceResponse.from(space)
        val detailResponse = SpaceDetailResponse.from(space)

        // then
        assertThat(listResponse.id).isEqualTo(11L)
        assertThat(listResponse.name).isEqualTo("판교 A룸")
        assertThat(listResponse.location).isEqualTo("판교")
        assertThat(listResponse.capacity).isEqualTo(6)
        assertThat(listResponse.pricePerSlot).isEqualTo(5000L)
        assertThat(listResponse.imagePath).isEqualTo("/images/room-a.png")
        assertThat(listResponse.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(listResponse.closingTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(listResponse.status).isEqualTo(SpaceStatus.ACTIVE)

        assertThat(detailResponse.id).isEqualTo(11L)
        assertThat(detailResponse.name).isEqualTo("판교 A룸")
        assertThat(detailResponse.description).isEqualTo("자연광이 좋은 회의실")
        assertThat(detailResponse.capacity).isEqualTo(6)
        assertThat(detailResponse.pricePerSlot).isEqualTo(5000L)
        assertThat(detailResponse.imagePath).isEqualTo("/images/room-a.png")
        assertThat(detailResponse.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(detailResponse.closingTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(detailResponse.status).isEqualTo(SpaceStatus.ACTIVE)
        assertThat(detailResponse.version).isEqualTo(3)
    }

    @Test
    @DisplayName("선택 필드 description·imagePath의 null은 두 응답에서 null로 유지된다")
    fun optionalFieldsStayNull() {
        // given
        val space =
            Space
                .builder()
                .id(11L)
                .name("판교 A룸")
                .location("판교")
                .description(null)
                .capacity(6)
                .pricePerSlot(5000L)
                .imagePath(null)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(3)
                .build()

        // then
        val listResponse = SpaceResponse.from(space)
        val detailResponse = SpaceDetailResponse.from(space)
        assertThat(listResponse.imagePath).isNull()
        assertThat(detailResponse.imagePath).isNull()
        assertThat(detailResponse.description).isNull()
    }

    @ParameterizedTest(name = "누락 필드: {0}")
    @MethodSource("missingRequiredFields")
    @DisplayName("필수 필드가 하나라도 null이면 목록 응답 팩토리는 필드명을 밝히며 실패한다")
    fun listFactory_missingRequiredField_failsWithFieldIdentity(
        field: String,
        space: Space,
    ) {
        assertThatThrownBy { SpaceResponse.from(space) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Space.$field")
            .hasMessageContaining("SpaceResponse")
    }

    @ParameterizedTest(name = "누락 필드: {0}")
    @MethodSource("missingRequiredFields")
    @DisplayName("필수 필드가 하나라도 null이면 상세 응답 팩토리는 필드명을 밝히며 실패한다")
    fun detailFactory_missingRequiredField_failsWithFieldIdentity(
        field: String,
        space: Space,
    ) {
        assertThatThrownBy { SpaceDetailResponse.from(space) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Space.$field")
            .hasMessageContaining("SpaceDetailResponse")
    }

    @Test
    @DisplayName("no-arg로 만든 불완전한 공간은 가짜 DTO 대신 id 누락으로 실패한다")
    fun noArgIncompleteSpace_failsWithoutFabricatingDto() {
        assertThatThrownBy { SpaceResponse.from(Space()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Space.id must not be null when creating a SpaceResponse")
        assertThatThrownBy { SpaceDetailResponse.from(Space()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Space.id must not be null when creating a SpaceDetailResponse")
    }

    @Test
    @DisplayName("영속화 전 id가 null인 완전한 공간은 엔티티로 허용되지만 응답 변환은 id 누락으로 실패한다")
    fun completeUnsavedSpaceWithNullId_isAllowedAsEntityButFailsConversion() {
        // given
        val unsaved =
            Space
                .builder()
                .id(null)
                .name("미저장 회의실")
                .location("강남")
                .description("설명")
                .capacity(4)
                .pricePerSlot(3000L)
                .imagePath(null)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build()

        // then
        assertThat(unsaved.id).isNull()
        assertThat(unsaved.name).isEqualTo("미저장 회의실")
        assertThatThrownBy { SpaceResponse.from(unsaved) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Space.id")
        assertThatThrownBy { SpaceDetailResponse.from(unsaved) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Space.id")
    }

    private fun completeSpace(): Space =
        Space
            .builder()
            .id(11L)
            .name("판교 A룸")
            .location("판교")
            .description("자연광이 좋은 회의실")
            .capacity(6)
            .pricePerSlot(5000L)
            .imagePath("/images/room-a.png")
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(18, 0))
            .status(SpaceStatus.ACTIVE)
            .version(3)
            .build()

    companion object {
        private fun spaceWith(
            id: Long? = 11L,
            name: String? = "판교 A룸",
            location: String? = "판교",
            capacity: Int? = 6,
            pricePerSlot: Long? = 5000L,
            openingTime: LocalTime? = LocalTime.of(9, 0),
            closingTime: LocalTime? = LocalTime.of(18, 0),
            status: SpaceStatus? = SpaceStatus.ACTIVE,
        ): Space =
            Space
                .builder()
                .id(id)
                .name(name)
                .location(location)
                .description("설명")
                .capacity(capacity)
                .pricePerSlot(pricePerSlot)
                .imagePath("/images/room-a.png")
                .openingTime(openingTime)
                .closingTime(closingTime)
                .status(status)
                .version(3)
                .build()

        @JvmStatic
        fun missingRequiredFields(): Stream<Arguments> =
            Stream.of(
                Arguments.of("id", spaceWith(id = null)),
                Arguments.of("name", spaceWith(name = null)),
                Arguments.of("location", spaceWith(location = null)),
                Arguments.of("capacity", spaceWith(capacity = null)),
                Arguments.of("pricePerSlot", spaceWith(pricePerSlot = null)),
                Arguments.of("openingTime", spaceWith(openingTime = null)),
                Arguments.of("closingTime", spaceWith(closingTime = null)),
                Arguments.of("status", spaceWith(status = null)),
            )
    }
}
