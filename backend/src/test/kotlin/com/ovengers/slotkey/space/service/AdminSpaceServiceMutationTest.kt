package com.ovengers.slotkey.space.service

import com.ovengers.slotkey.audit.service.AuditLogService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.time.Clock
import java.time.LocalTime
import java.util.Optional

/**
 * #191: 기본 PIT 대상에 AdminSpaceService를 추가했을 때 드러난 업무 규칙 공백을 고정한다.
 * 기존 [AdminSpaceServiceTest]는 비어 있는 Page 반환만 검증해 `getSpaces`의 DTO 매핑과
 * null keyword 정규화가 실제로 실행되지 않았다.
 */
@ExtendWith(MockitoExtension::class)
class AdminSpaceServiceMutationTest {
    @Mock
    private lateinit var spaceRepository: SpaceRepository

    @Mock
    private lateinit var auditLogService: AuditLogService

    @Mock
    private lateinit var occupiedSlotProvider: OccupiedSlotProvider

    @Mock
    private lateinit var clock: Clock

    @InjectMocks
    private lateinit var adminSpaceService: AdminSpaceService

    private fun space(
        id: Long,
        name: String,
    ): Space =
        Space
            .builder()
            .id(id)
            .name(name)
            .location("서울시 강남구")
            .capacity(6)
            .pricePerSlot(5000L)
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(18, 0))
            .status(SpaceStatus.ACTIVE)
            .version(0)
            .build()

    @Test
    @DisplayName("관리자 공간 목록 조회는 비어 있지 않은 결과를 SpaceDetailResponse로 매핑한다")
    fun getSpaces_nonEmptyPage_mapsToDetailResponse() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        val space = space(1L, "매핑 회의실")
        val page: Page<Space> = PageImpl(listOf(space), pageable, 1L)
        given(spaceRepository.searchSpacesByNameOrDescription(any(), any(), eq(pageable)))
            .willReturn(page)

        // when
        val result = adminSpaceService.getSpaces(SpaceStatus.ACTIVE, "회의실", pageable)

        // then
        assertThat(result.content).hasSize(1)
        assertThat(result.content[0].id).isEqualTo(1L)
        assertThat(result.content[0].name).isEqualTo("매핑 회의실")
        assertThat(result.content[0].pricePerSlot).isEqualTo(5000L)
    }

    @Test
    @DisplayName("관리자 공간 목록 조회 시 keyword가 null이면 null로 저장소에 전달한다")
    fun getSpaces_nullKeyword_passesNullKeyword() {
        // given
        val pageable: Pageable = PageRequest.of(0, 10)
        given(spaceRepository.searchSpacesByNameOrDescription(isNull(), isNull(), eq(pageable)))
            .willReturn(Page.empty(pageable))

        // when
        adminSpaceService.getSpaces(null, null, pageable)

        // then
        verify(spaceRepository).searchSpacesByNameOrDescription(null, null, pageable)
    }

    @Test
    @DisplayName("후보 운영시간이 유효하지 않으면 점유 슬롯 조회 전에 INVALID_OPERATING_HOURS를 던진다")
    fun updateSpace_invalidCandidateHours_rejectedBeforeOccupancyLookup() {
        // given: 기존 09:00~18:00에서 18:00~09:00으로 축소(역순) 요청
        val spaceId = 5L
        val existing = space(spaceId, "검증 회의실")
        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existing))

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
        assertThatThrownBy { adminSpaceService.updateSpace(spaceId, request, 1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        assertThat(existing.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(existing.closingTime).isEqualTo(LocalTime.of(18, 0))
        // 사전 검증이 사라지면 축소로 판단해 점유 슬롯 조회를 먼저 호출하고, 충돌 시 다른 오류코드를 던진다.
        verifyNoInteractions(occupiedSlotProvider, auditLogService)
    }

    @Test
    @DisplayName("openingTime만 잘못 지정하고 closingTime은 보내지 않아도 사전 검증에서 거절하고 점유 조회를 하지 않는다")
    fun updateSpace_invalidOpeningOnlyCandidate_rejectedBeforeOccupancyLookup() {
        // given: 기존 09:00~18:00에서 openingTime만 18:30으로 변경(역순), closingTime은 null로 유지
        val spaceId = 6L
        val existing = space(spaceId, "검증 회의실")
        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existing))

        val request =
            SpaceUpdateRequest(
                targetSpaceId = null,
                name = null,
                location = null,
                description = null,
                capacity = null,
                pricePerSlot = null,
                imagePath = null,
                openingTime = LocalTime.of(18, 30),
                closingTime = null,
                status = null,
            )

        // when & then
        assertThatThrownBy { adminSpaceService.updateSpace(spaceId, request, 1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        assertThat(existing.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(existing.closingTime).isEqualTo(LocalTime.of(18, 0))
        verifyNoInteractions(occupiedSlotProvider, auditLogService)
    }

    @Test
    @DisplayName("closingTime만 잘못 지정하고 openingTime은 보내지 않아도 사전 검증에서 거절하고 점유 조회를 하지 않는다")
    fun updateSpace_invalidClosingOnlyCandidate_rejectedBeforeOccupancyLookup() {
        // given: 기존 09:00~18:00에서 closingTime만 08:30으로 변경(역순), openingTime은 null로 유지
        val spaceId = 8L
        val existing = space(spaceId, "검증 회의실")
        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existing))

        val request =
            SpaceUpdateRequest(
                targetSpaceId = null,
                name = null,
                location = null,
                description = null,
                capacity = null,
                pricePerSlot = null,
                imagePath = null,
                openingTime = null,
                closingTime = LocalTime.of(8, 30),
                status = null,
            )

        // when & then
        assertThatThrownBy { adminSpaceService.updateSpace(spaceId, request, 1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        assertThat(existing.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(existing.closingTime).isEqualTo(LocalTime.of(18, 0))
        verifyNoInteractions(occupiedSlotProvider, auditLogService)
    }
}
