package com.ovengers.slotkey.space.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.dto.response.SlotResponse
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.policy.SpaceOperatingHoursPolicy
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class SpaceSlotAvailabilityServiceTest {
    @Mock
    private lateinit var spaceRepository: SpaceRepository

    @Mock
    private lateinit var occupiedSlotProvider: OccupiedSlotProvider

    private lateinit var operatingHoursPolicy: SpaceOperatingHoursPolicy
    private lateinit var availabilityService: SpaceSlotAvailabilityService

    // 고정 기준 시각: 2026-09-20T10:15:00 KST
    private val zoneId: ZoneId = ZoneId.of("Asia/Seoul")
    private val fixedNow: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 15, 0)
    private val clock: Clock = Clock.fixed(fixedNow.atZone(zoneId).toInstant(), zoneId)

    @BeforeEach
    fun setUp() {
        operatingHoursPolicy = SpaceOperatingHoursPolicy()
        availabilityService =
            SpaceSlotAvailabilityService(
                spaceRepository,
                operatingHoursPolicy,
                occupiedSlotProvider,
                clock,
            )
    }

    private fun createSpace(
        id: Long,
        status: SpaceStatus,
    ): Space =
        Space
            .builder()
            .id(id)
            .name("회의실 A")
            .location("강남점 3층")
            .capacity(6)
            .pricePerSlot(5000L)
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(12, 0)) // 09:00 ~ 12:00 (총 6슬롯)
            .status(status)
            .build()

    @Test
    @DisplayName("고정된 시각(10:15) 기준, 과거 슬롯(09:00, 09:30)은 비활성화되고 미래 슬롯(10:30~)은 활성화된다")
    fun getSlotAvailability_pastSlotsDisabled() {
        // given
        val spaceId = 1L
        val targetDate = LocalDate.of(2026, 9, 20)
        val space = createSpace(spaceId, SpaceStatus.ACTIVE)

        given(spaceRepository.findById(spaceId)).willReturn(Optional.of(space))
        given(occupiedSlotProvider.getOccupiedSlotStarts(spaceId, targetDate, fixedNow)).willReturn(emptySet())

        // when
        val response = availabilityService.getSlotAvailability(spaceId, targetDate)

        // then
        val slots: List<SlotResponse> = response.slots
        assertThat(slots).hasSize(6)

        // 09:00~09:30 -> 과거이므로 false
        assertThat(slots[0].slotStart).isEqualTo(LocalDateTime.of(2026, 9, 20, 9, 0))
        assertThat(slots[0].isAvailable).isFalse()

        // 09:30~10:00 -> 과거이므로 false
        assertThat(slots[1].slotStart).isEqualTo(LocalDateTime.of(2026, 9, 20, 9, 30))
        assertThat(slots[1].isAvailable).isFalse()

        // 10:00~10:30 -> start(10:00)가 now(10:15)보다 이전이므로 false
        assertThat(slots[2].slotStart).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 0))
        assertThat(slots[2].isAvailable).isFalse()

        // 10:30~11:00 -> 미래이므로 true
        assertThat(slots[3].slotStart).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 30))
        assertThat(slots[3].isAvailable).isTrue()

        // 11:00~11:30 -> 미래이므로 true
        assertThat(slots[4].slotStart).isEqualTo(LocalDateTime.of(2026, 9, 20, 11, 0))
        assertThat(slots[4].isAvailable).isTrue()
    }

    @Test
    @DisplayName("점유된 슬롯은 미래 시각이더라도 isAvailable이 false로 반환된다")
    fun getSlotAvailability_occupiedSlotIsFalse() {
        // given
        val spaceId = 1L
        val targetDate = LocalDate.of(2026, 9, 20)
        val space = createSpace(spaceId, SpaceStatus.ACTIVE)

        // 11:00 슬롯이 이미 점유됨
        val occupiedTime = LocalDateTime.of(2026, 9, 20, 11, 0)
        given(spaceRepository.findById(spaceId)).willReturn(Optional.of(space))
        given(occupiedSlotProvider.getOccupiedSlotStarts(spaceId, targetDate, fixedNow))
            .willReturn(setOf(occupiedTime))

        // when
        val response = availabilityService.getSlotAvailability(spaceId, targetDate)

        // then
        val slots = response.slots
        // 10:30 슬롯 -> 점유 안 됨 -> true
        assertThat(slots[3].isAvailable).isTrue()
        // 11:00 슬롯 -> 점유됨 -> false
        assertThat(slots[4].isAvailable).isFalse()
        // 11:30 슬롯 -> 점유 안 됨 -> true
        assertThat(slots[5].isAvailable).isTrue()
    }

    @Test
    @DisplayName("공간 상태가 INACTIVE이면 점유 데이터가 존재하더라도 모든 슬롯이 false로 반환된다")
    fun getSlotAvailability_inactiveSpaceWithOccupiedSlots_allSlotsFalse() {
        // given
        val spaceId = 1L
        val targetDate = LocalDate.of(2026, 9, 20)
        val inactiveSpace = createSpace(spaceId, SpaceStatus.INACTIVE)

        val occupiedTime = LocalDateTime.of(2026, 9, 20, 11, 0)
        given(spaceRepository.findById(spaceId)).willReturn(Optional.of(inactiveSpace))
        given(occupiedSlotProvider.getOccupiedSlotStarts(spaceId, targetDate, fixedNow))
            .willReturn(setOf(occupiedTime))

        // when
        val response = availabilityService.getSlotAvailability(spaceId, targetDate)

        // then
        assertThat(response.slots).allMatch { slot -> !slot.isAvailable }
    }

    @Test
    @DisplayName("요청된 spaceId와 date가 응답 DTO에 정확히 보존된다")
    fun getSlotAvailability_preservesSpaceIdAndDate() {
        // given
        val spaceId = 42L
        val targetDate = LocalDate.of(2026, 9, 20)
        val space = createSpace(spaceId, SpaceStatus.ACTIVE)

        given(spaceRepository.findById(spaceId)).willReturn(Optional.of(space))
        given(occupiedSlotProvider.getOccupiedSlotStarts(spaceId, targetDate, fixedNow)).willReturn(emptySet())

        // when
        val response = availabilityService.getSlotAvailability(spaceId, targetDate)

        // then
        assertThat(response.spaceId).isEqualTo(42L)
        assertThat(response.date).isEqualTo(targetDate)
    }

    @Test
    @DisplayName("존재하지 않는 spaceId 조회 시 SPACE_NOT_FOUND 예외를 던지고 occupiedSlotProvider를 호출하지 않는다")
    fun getSlotAvailability_spaceNotFound_throwsException() {
        // given
        val invalidId = 999L
        given(spaceRepository.findById(invalidId)).willReturn(Optional.empty())

        // when & then
        assertThatThrownBy { availabilityService.getSlotAvailability(invalidId, LocalDate.of(2026, 9, 20)) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_NOT_FOUND)

        verifyNoInteractions(occupiedSlotProvider)
    }

    @Test
    @DisplayName("현재 시각과 슬롯 시작 시각이 정확히 일치하면 예약 정책과 동일하게 해당 슬롯은 가용하지 않다")
    fun getSlotAvailability_exactStartTimeMatch_isUnavailable() {
        // given
        val spaceId = 1L
        val targetDate = LocalDate.of(2026, 9, 20)
        val space = createSpace(spaceId, SpaceStatus.ACTIVE)

        // 현재 시각을 10:30:00 정각으로 고정한 Clock 준비
        val exactNow = LocalDateTime.of(2026, 9, 20, 10, 30, 0)
        val exactClock = Clock.fixed(exactNow.atZone(zoneId).toInstant(), zoneId)
        val serviceWithExactClock =
            SpaceSlotAvailabilityService(
                spaceRepository,
                operatingHoursPolicy,
                occupiedSlotProvider,
                exactClock,
            )

        given(spaceRepository.findById(spaceId)).willReturn(Optional.of(space))
        given(occupiedSlotProvider.getOccupiedSlotStarts(spaceId, targetDate, exactNow)).willReturn(emptySet())

        // when
        val response = serviceWithExactClock.getSlotAvailability(spaceId, targetDate)

        // then
        // slots(3)은 10:30~11:00 슬롯. window.start() == now 이므로 즉시 예약할 수 없다.
        val slot1030 = response.slots[3]
        assertThat(slot1030.slotStart).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 30))
        assertThat(slot1030.isAvailable).isFalse()

        // 다음 슬롯은 아직 미래이므로 가용하다.
        assertThat(response.slots[4].isAvailable).isTrue()
    }
}
