package com.ovengers.slotkey.space.entity

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * #191: 기본 PIT에 Space$Companion을 포함했을 때 드러난 검증 공백을 고정한다.
 * 기존 [SpaceTest]는 30분 경계의 분(minute) 위반과 초(second) 위반만 확인해
 * 나노초 위반, :30 허용, null 요금 단독 검증, 수정 시 양쪽 운영시간 동시 위반이 비어 있었다.
 */
class SpaceMutationTest {
    private fun spaceWithHours(): Space =
        Space
            .builder()
            .id(1L)
            .name("검증 회의실")
            .location("서울시 강남구")
            .capacity(6)
            .pricePerSlot(5000L)
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(18, 0))
            .status(SpaceStatus.ACTIVE)
            .version(0)
            .build()

    @Test
    @DisplayName("validatePricePerSlot은 null 요금을 INVALID_PRICE_UNIT으로 거절한다")
    fun validatePricePerSlot_nullPrice_throwsInvalidPriceUnit() {
        assertThatThrownBy { Space.validatePricePerSlot(null) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PRICE_UNIT)
    }

    @Test
    @DisplayName("validateOperatingHours는 나노초가 0이 아니면 INVALID_OPERATING_HOURS를 던진다")
    fun validateOperatingHours_nonZeroNano_throwsInvalidOperatingHours() {
        assertThatThrownBy {
            Space.validateOperatingHours(LocalTime.of(9, 0, 0, 1), LocalTime.of(18, 0))
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)
    }

    @Test
    @DisplayName("validateOperatingHours는 :30 경계 시각을 유효한 운영시간으로 허용한다")
    fun validateOperatingHours_halfHourBoundary_isAccepted() {
        assertThatCode {
            Space.validateOperatingHours(LocalTime.of(9, 30), LocalTime.of(18, 30))
        }.doesNotThrowAnyException()
    }

    @Test
    @DisplayName("updateDetail은 양쪽 운영시간을 동시에 잘못 지정하면 예외를 던지고 기존 운영시간을 유지한다")
    fun updateDetail_bothHoursInvalid_throwsAndPreservesHours() {
        // given
        val space = spaceWithHours()
        val request =
            SpaceUpdateRequest(
                targetSpaceId = null,
                name = null,
                location = null,
                description = null,
                capacity = null,
                pricePerSlot = null,
                imagePath = null,
                openingTime = LocalTime.of(18, 0),
                closingTime = LocalTime.of(9, 0),
                status = null,
            )

        // when & then
        assertThatThrownBy { space.updateDetail(request) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        assertThat(space.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(space.closingTime).isEqualTo(LocalTime.of(18, 0))
    }
}
