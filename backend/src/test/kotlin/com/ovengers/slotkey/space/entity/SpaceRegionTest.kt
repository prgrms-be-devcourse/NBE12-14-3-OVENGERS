package com.ovengers.slotkey.space.entity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class SpaceRegionTest {
    @ParameterizedTest
    @ValueSource(strings = ["판교", "강남", "하남"])
    @DisplayName("판교·강남·하남은 지원하는 지역이다")
    fun supportsThreeRegions(location: String) {
        assertThat(SpaceRegion.isSupported(location)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "서울", "판교 ", "서울시 강남구", "PANGYO"])
    @DisplayName("그 밖의 값(공백·부분 일치·주소 문자열 포함)은 지원하지 않는 지역이다")
    fun rejectsOtherValues(location: String) {
        assertThat(SpaceRegion.isSupported(location)).isFalse()
    }

    @Test
    @DisplayName("null은 지원하지 않는 지역이다")
    fun rejectsNull() {
        assertThat(SpaceRegion.isSupported(null)).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "PANGYO, 판교",
        "GANGNAM, 강남",
        "HANAM, 하남",
    )
    @DisplayName("지역 Enum은 spaces.location에 저장되는 한글 label과 대응한다")
    fun labelMatchesStoredLocation(
        region: SpaceRegion,
        expectedLabel: String,
    ) {
        assertThat(region.label()).isEqualTo(expectedLabel)
    }
}
