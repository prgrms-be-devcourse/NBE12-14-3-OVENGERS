package com.ovengers.slotkey.space.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.dto.request.SpaceSearchCondition
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Optional
import java.util.stream.Stream

@ExtendWith(MockitoExtension::class)
class SpaceQueryServiceTest {
    @Mock
    private lateinit var spaceRepository: SpaceRepository

    @Mock
    private lateinit var occupiedSlotProvider: OccupiedSlotProvider

    @Mock
    private lateinit var clock: Clock

    @InjectMocks
    private lateinit var spaceQueryService: SpaceQueryService

    private val noExclusion: List<Long> = listOf(-1L)

    private fun createSpace(
        id: Long,
        name: String,
    ): Space =
        Space
            .builder()
            .id(id)
            .name(name)
            .location("서울시 강남구")
            .description("설명")
            .capacity(6)
            .pricePerSlot(5000L)
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(18, 0))
            .status(SpaceStatus.ACTIVE)
            .version(0)
            .build()

    private fun conditionOf(keyword: String?): SpaceSearchCondition = SpaceSearchCondition(keyword, null, null, null, null, null, null)

    @Test
    @DisplayName("keyword가 없으면 검색 조건 없이 searchSpaces를 호출한다")
    fun getSpacesPage_blankKeyword_callsSearchSpaces() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val spaces = listOf(createSpace(1L, "회의실 1"), createSpace(2L, "회의실 2"))
        val expectedPage: Page<Space> = PageImpl(spaces, pageable, spaces.size.toLong())

        given(
            spaceRepository.searchSpaces(
                eq(SpaceStatus.ACTIVE),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(noExclusion),
                eq(pageable),
            ),
        ).willReturn(expectedPage)

        // when
        val actualPage = spaceQueryService.getSpacesPage(pageable, conditionOf(null))

        // then
        assertThat(actualPage.content).hasSize(2)
        verify(spaceRepository).searchSpaces(
            SpaceStatus.ACTIVE,
            null,
            null,
            null,
            null,
            null,
            null,
            noExclusion,
            pageable,
        )
    }

    @Test
    @DisplayName("유효한 keyword가 주어지면 trim 정규화 후 searchSpaces에 전달한다")
    fun getSpacesPage_validKeyword_callsSearchSpaces() {
        // given
        val keyword = "  회의실  "
        val pageable: Pageable = PageRequest.of(0, 10)
        val spaces = listOf(createSpace(1L, "회의실 1"))
        val expectedPage: Page<Space> = PageImpl(spaces, pageable, spaces.size.toLong())

        given(
            spaceRepository.searchSpaces(
                eq(SpaceStatus.ACTIVE),
                eq("회의실"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(noExclusion),
                eq(pageable),
            ),
        ).willReturn(expectedPage)

        // when
        val actualPage = spaceQueryService.getSpacesPage(pageable, conditionOf(keyword))

        // then
        assertThat(actualPage.content).hasSize(1)
        assertThat(actualPage.content[0].name).isEqualTo("회의실 1")
        verify(spaceRepository).searchSpaces(
            SpaceStatus.ACTIVE,
            "회의실",
            null,
            null,
            null,
            null,
            null,
            noExclusion,
            pageable,
        )
    }

    @Test
    @DisplayName("keyword와 location이 공백 문자열이면 null로 정규화되어 전달된다")
    fun getSpacesPage_whitespaceKeywordAndLocation_normalizedToNull() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val condition = SpaceSearchCondition("   ", "   \t", null, null, null, null, null)
        val expectedPage: Page<Space> = PageImpl(emptyList(), pageable, 0L)

        given(
            spaceRepository.searchSpaces(
                eq(SpaceStatus.ACTIVE),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(noExclusion),
                eq(pageable),
            ),
        ).willReturn(expectedPage)

        // when
        spaceQueryService.getSpacesPage(pageable, condition)

        // then
        verify(spaceRepository).searchSpaces(
            SpaceStatus.ACTIVE,
            null,
            null,
            null,
            null,
            null,
            null,
            noExclusion,
            pageable,
        )
    }

    @Test
    @DisplayName("minPrice가 0이고 maxPrice가 0이면 정상적으로 searchSpaces에 전달된다")
    fun getSpacesPage_zeroPrices_allowed() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val condition = SpaceSearchCondition(null, null, 0L, 0L, null, null, null)
        val expectedPage: Page<Space> = PageImpl(emptyList(), pageable, 0L)

        given(
            spaceRepository.searchSpaces(
                eq(SpaceStatus.ACTIVE),
                isNull(),
                isNull(),
                eq(0L),
                eq(0L),
                isNull(),
                isNull(),
                eq(noExclusion),
                eq(pageable),
            ),
        ).willReturn(expectedPage)

        // when
        spaceQueryService.getSpacesPage(pageable, condition)

        // then
        verify(spaceRepository).searchSpaces(
            SpaceStatus.ACTIVE,
            null,
            null,
            0L,
            0L,
            null,
            null,
            noExclusion,
            pageable,
        )
    }

    @Test
    @DisplayName("minPrice가 음수이면 VALIDATION_FAILED 예외가 발생하고 검색을 호출하지 않는다")
    fun getSpacesPage_negativeMinPrice_throwsException() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val condition = SpaceSearchCondition(null, null, -1L, 10000L, null, null, null)

        // when & then
        assertThatThrownBy { spaceQueryService.getSpacesPage(pageable, condition) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED)

        verifyNoInteractions(spaceRepository)
        verifyNoInteractions(occupiedSlotProvider)
    }

    @Test
    @DisplayName("maxPrice가 음수이면 VALIDATION_FAILED 예외가 발생한다")
    fun getSpacesPage_negativeMaxPrice_throwsException() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val condition = SpaceSearchCondition(null, null, 0L, -500L, null, null, null)

        // when & then
        assertThatThrownBy { spaceQueryService.getSpacesPage(pageable, condition) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED)

        verifyNoInteractions(spaceRepository)
    }

    @Test
    @DisplayName("minPrice 없이 maxPrice만 음수이면 다른 가격 검증과 무관하게 VALIDATION_FAILED 예외가 발생한다")
    fun getSpacesPage_onlyNegativeMaxPrice_throwsValidationFailedWithoutSideEffects() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val condition = SpaceSearchCondition(null, null, null, -1L, null, null, null)

        // when & then
        assertThatThrownBy { spaceQueryService.getSpacesPage(pageable, condition) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED)

        verifyNoInteractions(spaceRepository)
        verifyNoInteractions(occupiedSlotProvider)
    }

    @Test
    @DisplayName("minPrice가 maxPrice보다 크면 VALIDATION_FAILED 예외가 발생한다")
    fun getSpacesPage_minPriceGreaterThanMaxPrice_throwsException() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val condition = SpaceSearchCondition(null, null, 10000L, 5000L, null, null, null)

        // when & then
        assertThatThrownBy { spaceQueryService.getSpacesPage(pageable, condition) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED)

        verifyNoInteractions(spaceRepository)
    }

    @ParameterizedTest(name = "부분 시간 입력: date={0}, start={1}, end={2}")
    @MethodSource("partialTimeFilters")
    @DisplayName("date, startTime, endTime 중 일부만 입력되면 VALIDATION_FAILED 예외가 발생한다")
    fun getSpacesPage_partialTimeFilter_throwsValidationFailed(
        date: LocalDate?,
        start: LocalTime?,
        end: LocalTime?,
    ) {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val condition = SpaceSearchCondition(null, null, null, null, date, start, end)

        // when & then
        assertThatThrownBy { spaceQueryService.getSpacesPage(pageable, condition) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED)

        verifyNoInteractions(spaceRepository)
        verifyNoInteractions(occupiedSlotProvider)
    }

    @ParameterizedTest(name = "30분 단위 위반: start={0}, end={1}")
    @MethodSource("nonAlignedTimes")
    @DisplayName("시작 또는 종료 시간이 30분 단위가 아니거나 초/나노초가 있으면 VALIDATION_FAILED 예외가 발생한다")
    fun getSpacesPage_nonAlignedTime_throwsValidationFailed(
        start: LocalTime?,
        end: LocalTime?,
    ) {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val date = LocalDate.of(2026, 10, 1)
        val condition = SpaceSearchCondition(null, null, null, null, date, start, end)

        // when & then
        assertThatThrownBy { spaceQueryService.getSpacesPage(pageable, condition) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED)

        verifyNoInteractions(spaceRepository)
        verifyNoInteractions(occupiedSlotProvider)
    }

    @Test
    @DisplayName("시작 시각이 종료 시각과 같으면 INVALID_TIME_RANGE 예외가 발생한다")
    fun getSpacesPage_sameStartAndEndTime_throwsInvalidTimeRange() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val date = LocalDate.of(2026, 9, 23)
        val condition =
            SpaceSearchCondition(null, null, null, null, date, LocalTime.of(14, 0), LocalTime.of(14, 0))

        // when & then
        assertThatThrownBy { spaceQueryService.getSpacesPage(pageable, condition) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TIME_RANGE)

        verify(spaceRepository, never()).searchSpaces(any(), any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    @DisplayName("시작 시각이 종료 시각보다 늦으면 INVALID_TIME_RANGE 예외가 발생한다")
    fun getSpacesPage_invalidTimeRange_throwsException() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val date = LocalDate.of(2026, 9, 23)
        val condition =
            SpaceSearchCondition(null, null, null, null, date, LocalTime.of(15, 0), LocalTime.of(14, 0))

        // when & then
        assertThatThrownBy { spaceQueryService.getSpacesPage(pageable, condition) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TIME_RANGE)

        verify(spaceRepository, never()).searchSpaces(any(), any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    @DisplayName("시간대 필터를 지정하면 영업시간과 점유 슬롯 제외 목록을 searchSpaces에 전달한다")
    fun getSpacesPage_withTimeFilter_passesTimesAndExcludesOccupiedSpaces() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val date = LocalDate.of(2026, 9, 23)
        val startTime = LocalTime.of(14, 0)
        val endTime = LocalTime.of(15, 0)
        val condition =
            SpaceSearchCondition(null, "강남", null, null, date, startTime, endTime)

        val fixedClock =
            Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneId.of("Asia/Seoul"))
        given(clock.zone).willReturn(fixedClock.zone)
        given(clock.instant()).willReturn(fixedClock.instant())

        given(occupiedSlotProvider.getOccupiedSpaceIds(any(), any(), any()))
            .willReturn(setOf(9L, 10L))

        val spaces = listOf(createSpace(1L, "회의실 1"))
        val expectedPage: Page<Space> = PageImpl(spaces, pageable, spaces.size.toLong())
        given(
            spaceRepository.searchSpaces(
                eq(SpaceStatus.ACTIVE),
                isNull(),
                eq("강남"),
                isNull(),
                isNull(),
                eq(startTime),
                eq(endTime),
                any(),
                eq(pageable),
            ),
        ).willReturn(expectedPage)

        // when
        val actualPage = spaceQueryService.getSpacesPage(pageable, condition)

        // then
        assertThat(actualPage.content).hasSize(1)
        verify(spaceRepository).searchSpaces(
            eq(SpaceStatus.ACTIVE),
            isNull(),
            eq("강남"),
            isNull(),
            isNull(),
            eq(startTime),
            eq(endTime),
            argThatContainsExactlyInAnyOrder(9L, 10L),
            eq(pageable),
        )
    }

    @ParameterizedTest(name = "정상 30분 경계: start={0}, end={1}")
    @MethodSource("halfHourAlignedFilters")
    @DisplayName("시작 또는 종료가 :30이어도 검색되고 전달된 시간 범위와 현재 시각이 정확하다")
    fun getSpacesPage_halfHourBoundary_succeeds(
        start: LocalTime,
        end: LocalTime,
    ) {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val date = LocalDate.of(2026, 9, 23)
        val condition = SpaceSearchCondition(null, null, null, null, date, start, end)

        val fixedClock =
            Clock.fixed(Instant.parse("2026-09-23T05:30:00Z"), ZoneId.of("Asia/Seoul"))
        given(clock.zone).willReturn(fixedClock.zone)
        given(clock.instant()).willReturn(fixedClock.instant())

        given(occupiedSlotProvider.getOccupiedSpaceIds(any(), any(), any()))
            .willReturn(setOf(9L, 10L))

        val expectedSpace = createSpace(7L, "경계 회의실")
        val expectedPage: Page<Space> = PageImpl(listOf(expectedSpace), pageable, 1L)
        given(
            spaceRepository.searchSpaces(
                eq(SpaceStatus.ACTIVE),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(start),
                eq(end),
                any(),
                eq(pageable),
            ),
        ).willReturn(expectedPage)

        // when
        val actualPage = spaceQueryService.getSpacesPage(pageable, condition)

        // then
        assertThat(actualPage.content).containsExactly(expectedSpace)
        assertThat(actualPage.totalElements).isEqualTo(1L)

        verify(occupiedSlotProvider).getOccupiedSpaceIds(
            LocalDateTime.of(date, start),
            LocalDateTime.of(date, end),
            LocalDateTime.of(2026, 9, 23, 14, 30),
        )
        verify(spaceRepository).searchSpaces(
            eq(SpaceStatus.ACTIVE),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq(start),
            eq(end),
            argThatContainsExactlyInAnyOrder(9L, 10L),
            eq(pageable),
        )
    }

    @Test
    @DisplayName(":30 경계 시간대에 점유 공간이 없으면 제외 sentinel로 검색된다")
    fun getSpacesPage_halfHourBoundaryWithoutOccupiedSpaces_usesNoExclusionSentinel() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val date = LocalDate.of(2026, 9, 23)
        val startTime = LocalTime.of(14, 30)
        val endTime = LocalTime.of(15, 30)
        val condition = SpaceSearchCondition(null, null, null, null, date, startTime, endTime)

        val fixedClock =
            Clock.fixed(Instant.parse("2026-09-23T05:30:00Z"), ZoneId.of("Asia/Seoul"))
        given(clock.zone).willReturn(fixedClock.zone)
        given(clock.instant()).willReturn(fixedClock.instant())

        given(occupiedSlotProvider.getOccupiedSpaceIds(any(), any(), any()))
            .willReturn(emptySet())

        val expectedSpace = createSpace(8L, "빈 시간대 회의실")
        val expectedPage: Page<Space> = PageImpl(listOf(expectedSpace), pageable, 1L)
        given(
            spaceRepository.searchSpaces(
                eq(SpaceStatus.ACTIVE),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(startTime),
                eq(endTime),
                eq(noExclusion),
                eq(pageable),
            ),
        ).willReturn(expectedPage)

        // when
        val actualPage = spaceQueryService.getSpacesPage(pageable, condition)

        // then
        assertThat(actualPage.content).containsExactly(expectedSpace)
        verify(spaceRepository).searchSpaces(
            eq(SpaceStatus.ACTIVE),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq(startTime),
            eq(endTime),
            eq(noExclusion),
            eq(pageable),
        )
    }

    private fun argThatContainsExactlyInAnyOrder(vararg ids: Long): List<Long>? =
        argThat<List<Long>?> { list ->
            list != null && list.size == ids.size && list.containsAll(ids.toList())
        }

    @Test
    @DisplayName("존재하는 공간 ID로 상세 조회 시 공간 엔티티를 반환한다")
    fun getSpaceDetailById_success() {
        // given
        val spaceId = 1L
        val space = createSpace(spaceId, "회의실 A")
        given(spaceRepository.findById(spaceId)).willReturn(Optional.of(space))

        // when
        val result = spaceQueryService.getSpaceDetailById(spaceId)

        // then
        assertThat(result).isNotNull()
        assertThat(result.id).isEqualTo(spaceId)
        assertThat(result.name).isEqualTo("회의실 A")
        verify(spaceRepository).findById(spaceId)
    }

    @Test
    @DisplayName("존재하지 않는 공간 ID로 상세 조회 시 SPACE_NOT_FOUND 예외가 발생한다")
    fun getSpaceDetailById_notFound_throwsException() {
        // given
        val notFoundId = 999L
        given(spaceRepository.findById(notFoundId)).willReturn(Optional.empty())

        // when & then
        assertThatThrownBy { spaceQueryService.getSpaceDetailById(notFoundId) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_NOT_FOUND)

        verify(spaceRepository).findById(notFoundId)
    }

    companion object {
        @JvmStatic
        fun partialTimeFilters(): Stream<Arguments> =
            Stream.of(
                Arguments.of(LocalDate.of(2026, 10, 1), null, null),
                Arguments.of(null, LocalTime.of(10, 0), null),
                Arguments.of(null, null, LocalTime.of(12, 0)),
                Arguments.of(LocalDate.of(2026, 10, 1), LocalTime.of(10, 0), null),
                Arguments.of(LocalDate.of(2026, 10, 1), null, LocalTime.of(12, 0)),
                Arguments.of(null, LocalTime.of(10, 0), LocalTime.of(12, 0)),
            )

        @JvmStatic
        fun halfHourAlignedFilters(): Stream<Arguments> =
            Stream.of(
                Arguments.of(LocalTime.of(14, 30), LocalTime.of(15, 0)),
                Arguments.of(LocalTime.of(14, 0), LocalTime.of(15, 30)),
                Arguments.of(LocalTime.of(14, 30), LocalTime.of(15, 30)),
            )

        @JvmStatic
        fun nonAlignedTimes(): Stream<Arguments> =
            Stream.of(
                Arguments.of(LocalTime.of(10, 15), LocalTime.of(11, 0)),
                Arguments.of(LocalTime.of(10, 0), LocalTime.of(11, 45)),
                Arguments.of(LocalTime.of(10, 0, 1), LocalTime.of(11, 0)),
                Arguments.of(LocalTime.of(10, 0), LocalTime.of(11, 0, 1)),
                Arguments.of(LocalTime.of(10, 0, 0, 500), LocalTime.of(11, 0)),
                Arguments.of(LocalTime.of(10, 0), LocalTime.of(11, 0, 0, 100)),
            )
    }
}
