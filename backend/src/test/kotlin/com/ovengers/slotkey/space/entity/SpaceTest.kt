package com.ovengers.slotkey.space.entity

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalTime

class SpaceTest {
    private fun createDefaultSpace(): Space =
        Space
            .builder()
            .id(1L)
            .name("회의실 A")
            .location("강남역 1번 출구")
            .description("조용하고 쾌적한 6인 회의실")
            .capacity(6)
            .pricePerSlot(5000L)
            .imagePath("/images/room-a.jpg")
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(18, 0))
            .status(SpaceStatus.ACTIVE)
            .version(0)
            .build()

    @Test
    @DisplayName("가격 변경 시 pricePerSlot이 갱신되고 version이 1 증가한다")
    fun updateDetail_priceChanged_versionIncrements() {
        // given
        val space = createDefaultSpace()
        val request =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                6000L,
                null,
                null,
                null,
                null,
            )

        // when
        space.updateDetail(request)

        // then
        assertThat(space.pricePerSlot).isEqualTo(6000L)
        assertThat(space.version).isEqualTo(1)
    }

    @Test
    @DisplayName("동일한 가격으로 재전송 시 version이 유지된다")
    fun updateDetail_samePrice_versionMaintained() {
        // given
        val space = createDefaultSpace()
        val request =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                5000L,
                null,
                null,
                null,
                null,
            )

        // when
        space.updateDetail(request)

        // then
        assertThat(space.pricePerSlot).isEqualTo(5000L)
        assertThat(space.version).isEqualTo(0)
    }

    @Test
    @DisplayName("가격 외의 정보(이름, 위치, 설명, 인원, 운영시간, 상태)만 수정 시 version이 유지된다")
    fun updateDetail_nonPriceFieldsChanged_versionMaintained() {
        // given
        val space = createDefaultSpace()
        val request =
            SpaceUpdateRequest(
                null,
                "회의실 B",
                "역삼역 2번 출구",
                "새로운 설명",
                8,
                null,
                "/images/room-b.jpg",
                LocalTime.of(10, 0),
                LocalTime.of(20, 0),
                SpaceStatus.INACTIVE,
            )

        // when
        space.updateDetail(request)

        // then
        assertThat(space.name).isEqualTo("회의실 B")
        assertThat(space.location).isEqualTo("역삼역 2번 출구")
        assertThat(space.description).isEqualTo("새로운 설명")
        assertThat(space.capacity).isEqualTo(8)
        assertThat(space.imagePath).isEqualTo("/images/room-a.jpg") // updateDetail로는 imagePath가 변경되지 않음
        assertThat(space.openingTime).isEqualTo(LocalTime.of(10, 0))
        assertThat(space.closingTime).isEqualTo(LocalTime.of(20, 0))
        assertThat(space.status).isEqualTo(SpaceStatus.INACTIVE)
        assertThat(space.pricePerSlot).isEqualTo(5000L)
        assertThat(space.version).isEqualTo(0)
    }

    @Test
    @DisplayName("이름·인원만 수정하면 요청이 null인 위치·설명·가격·이미지·운영시간·상태가 보존되고 version은 유지된다")
    fun updateDetail_partialNonPriceFields_preservesNullRequestFields() {
        // given
        val space = createDefaultSpace()
        val request =
            SpaceUpdateRequest(
                null,
                "회의실 C",
                null,
                null,
                10,
                null,
                null,
                null,
                null,
                null,
            )

        // when
        space.updateDetail(request)

        // then
        assertThat(space.name).isEqualTo("회의실 C")
        assertThat(space.capacity).isEqualTo(10)
        assertThat(space.location).isEqualTo("강남역 1번 출구")
        assertThat(space.description).isEqualTo("조용하고 쾌적한 6인 회의실")
        assertThat(space.pricePerSlot).isEqualTo(5000L)
        assertThat(space.imagePath).isEqualTo("/images/room-a.jpg")
        assertThat(space.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(space.closingTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(space.status).isEqualTo(SpaceStatus.ACTIVE)
        assertThat(space.version).isEqualTo(0)
    }

    @Test
    @DisplayName("가격과 설명만 수정하면 요청이 null인 이름·위치·이미지·운영시간·상태가 보존되고 version만 1 증가한다")
    fun updateDetail_partialPriceChange_preservesNullRequestFieldsAndImageRule() {
        // given
        val space = createDefaultSpace()
        val request =
            SpaceUpdateRequest(
                null,
                null,
                null,
                "새로운 설명",
                null,
                6000L,
                null,
                null,
                null,
                null,
            )

        // when
        space.updateDetail(request)

        // then
        assertThat(space.pricePerSlot).isEqualTo(6000L)
        assertThat(space.description).isEqualTo("새로운 설명")
        assertThat(space.version).isEqualTo(1)
        assertThat(space.name).isEqualTo("회의실 A")
        assertThat(space.location).isEqualTo("강남역 1번 출구")
        assertThat(space.imagePath).isEqualTo("/images/room-a.jpg")
        assertThat(space.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(space.closingTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(space.status).isEqualTo(SpaceStatus.ACTIVE)
    }

    @Test
    @DisplayName("updateImagePath로 대표 사진 변경 시 imagePath가 변경되고 version은 유지된다")
    fun updateImagePath_imagePathChanged_versionMaintained() {
        // given
        val space = createDefaultSpace()

        // when
        space.updateImagePath("/api/v1/space-images/new-image.jpg")

        // then
        assertThat(space.imagePath).isEqualTo("/api/v1/space-images/new-image.jpg")
        assertThat(space.version).isEqualTo(0)
    }

    @Test
    @DisplayName("ABA 가격 변경(5,000 -> 6,000 -> 5,000) 시 최종 가격은 5,000원이고 version은 2가 된다")
    fun updateDetail_abaPriceChange_versionIncrementsTwice() {
        // given
        val space = createDefaultSpace()

        // when 1: 5,000 -> 6,000
        space.updateDetail(
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                6000L,
                null,
                null,
                null,
                null,
            ),
        )
        assertThat(space.pricePerSlot).isEqualTo(6000L)
        assertThat(space.version).isEqualTo(1)

        // when 2: 6,000 -> 5,000
        space.updateDetail(
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                5000L,
                null,
                null,
                null,
                null,
            ),
        )

        // then
        assertThat(space.pricePerSlot).isEqualTo(5000L)
        assertThat(space.version).isEqualTo(2)
    }

    @Test
    @DisplayName("openingTime만 수정 시 기존 closingTime보다 이르면 운영시간이 갱신된다")
    fun updateDetail_openingTimeOnly_valid_success() {
        // given: 09:00 ~ 18:00
        val space = createDefaultSpace()
        val request =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalTime.of(8, 0),
                null,
                null,
            )

        // when
        space.updateDetail(request)

        // then
        assertThat(space.openingTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(space.closingTime).isEqualTo(LocalTime.of(18, 0))
    }

    @Test
    @DisplayName("openingTime만 수정 시 기존 closingTime과 같거나 늦으면 INVALID_OPERATING_HOURS 예외가 발생하고 운영시간이 유지된다")
    fun updateDetail_openingTimeOnly_invalid_throwsException() {
        // given: 09:00 ~ 18:00
        val space = createDefaultSpace()
        val sameTimeRequest =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalTime.of(18, 0),
                null,
                null,
            )

        // when & then (같을 때)
        assertThatThrownBy { space.updateDetail(sameTimeRequest) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)
        assertThat(space.openingTime).isEqualTo(LocalTime.of(9, 0))

        // when & then (더 늦을 때)
        val laterRequest =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalTime.of(19, 0),
                null,
                null,
            )
        assertThatThrownBy { space.updateDetail(laterRequest) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)
        assertThat(space.openingTime).isEqualTo(LocalTime.of(9, 0))
    }

    @Test
    @DisplayName("closingTime만 수정 시 기존 openingTime보다 늦으면 운영시간이 갱신된다")
    fun updateDetail_closingTimeOnly_valid_success() {
        // given: 09:00 ~ 18:00
        val space = createDefaultSpace()
        val request =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalTime.of(21, 0),
                null,
            )

        // when
        space.updateDetail(request)

        // then
        assertThat(space.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(space.closingTime).isEqualTo(LocalTime.of(21, 0))
    }

    @Test
    @DisplayName("closingTime만 수정 시 기존 openingTime과 같거나 이르면 INVALID_OPERATING_HOURS 예외가 발생하고 운영시간이 유지된다")
    fun updateDetail_closingTimeOnly_invalid_throwsException() {
        // given: 09:00 ~ 18:00
        val space = createDefaultSpace()
        val sameTimeRequest =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalTime.of(9, 0),
                null,
            )

        // when & then (같을 때)
        assertThatThrownBy { space.updateDetail(sameTimeRequest) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)
        assertThat(space.closingTime).isEqualTo(LocalTime.of(18, 0))

        // when & then (더 이를 때)
        val earlierRequest =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalTime.of(8, 0),
                null,
            )
        assertThatThrownBy { space.updateDetail(earlierRequest) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)
        assertThat(space.closingTime).isEqualTo(LocalTime.of(18, 0))
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -100L, -5000L, 5050L, 1050L, 99L])
    @DisplayName("가격 수정 시 0, 음수 또는 100원 단위가 아닌 값이면 INVALID_PRICE_UNIT 예외가 발생한다")
    fun updateDetail_invalidPrice_throwsException(invalidPrice: Long) {
        // given
        val space = createDefaultSpace()
        val request =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                invalidPrice,
                null,
                null,
                null,
                null,
            )

        // when & then
        assertThatThrownBy { space.updateDetail(request) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PRICE_UNIT)
        assertThat(space.pricePerSlot).isEqualTo(5000L)
        assertThat(space.version).isEqualTo(0)
    }

    @Test
    @DisplayName("updateStatus 호출 시 공간의 상태가 변경된다")
    fun updateStatus_changesStatus() {
        // given
        val space = createDefaultSpace()
        assertThat(space.status).isEqualTo(SpaceStatus.ACTIVE)

        // when
        space.updateStatus(SpaceStatus.INACTIVE)

        // then
        assertThat(space.status).isEqualTo(SpaceStatus.INACTIVE)
    }

    @Test
    @DisplayName("updateStatus에 null 전달 시 status가 null로 갱신되고 version은 불변을 유지한다")
    fun updateStatus_nullStatus_setsNullAndMaintainsVersion() {
        // given
        val space = createDefaultSpace()
        assertThat(space.status).isEqualTo(SpaceStatus.ACTIVE)
        val initialVersion = space.version

        // when
        space.updateStatus(null)

        // then
        assertThat(space.status).isNull()
        assertThat(space.version).isEqualTo(initialVersion)
    }

    @Test
    @DisplayName("validateOperatingHours는 null, 시간 순서 오류 또는 30분 경계가 아닌 시각에 예외를 발생시킨다")
    fun validateOperatingHours_throwsException() {
        assertThatThrownBy { Space.validateOperatingHours(null, LocalTime.of(18, 0)) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        assertThatThrownBy { Space.validateOperatingHours(LocalTime.of(9, 0), null) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        assertThatThrownBy { Space.validateOperatingHours(LocalTime.of(18, 0), LocalTime.of(9, 0)) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        assertThatThrownBy { Space.validateOperatingHours(LocalTime.of(9, 0), LocalTime.of(9, 0)) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        assertThatThrownBy { Space.validateOperatingHours(LocalTime.of(9, 15), LocalTime.of(18, 0)) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        assertThatThrownBy { Space.validateOperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 30, 1)) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)
    }

    @Test
    @DisplayName("Java no-arg 생성자 호출 시 참조 필드는 null이고 version은 0이다")
    fun noArgConstructor_referenceFieldsNullAndVersionZero() {
        val space = Space()
        assertThat(space.id).isNull()
        assertThat(space.name).isNull()
        assertThat(space.location).isNull()
        assertThat(space.description).isNull()
        assertThat(space.capacity).isNull()
        assertThat(space.pricePerSlot).isNull()
        assertThat(space.imagePath).isNull()
        assertThat(space.openingTime).isNull()
        assertThat(space.closingTime).isNull()
        assertThat(space.status).isNull()
        assertThat(space.version).isEqualTo(0)
    }

    @Test
    @DisplayName("Java builder에 null 필드를 넘겨도 정상 생성되고 boxed Long/Integer 및 int version 계약을 유지한다")
    fun builder_nullableAndBoxedTypeCompatibility() {
        val space =
            Space
                .builder()
                .id(null)
                .name(null)
                .location(null)
                .description(null)
                .capacity(null)
                .pricePerSlot(null)
                .imagePath(null)
                .openingTime(null)
                .closingTime(null)
                .status(null)
                .build()

        assertThat(space.id).isNull()
        assertThat(space.capacity).isNull()
        assertThat(space.pricePerSlot).isNull()
        assertThat(space.version).isEqualTo(0)

        val boxedId: Long? = space.id
        val boxedCapacity: Int? = space.capacity
        val boxedPrice: Long? = space.pricePerSlot
        val primitiveVersion: Int = space.version

        assertThat(boxedId).isNull()
        assertThat(boxedCapacity).isNull()
        assertThat(boxedPrice).isNull()
        assertThat(primitiveVersion).isEqualTo(0)
    }
}
