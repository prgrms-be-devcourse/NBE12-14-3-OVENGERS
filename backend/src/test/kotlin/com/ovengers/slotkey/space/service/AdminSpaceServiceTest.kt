package com.ovengers.slotkey.space.service

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.service.AuditLogService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.space.dto.request.SpaceCreateRequest
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest
import com.ovengers.slotkey.space.dto.response.SpaceDetailResponse
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Optional
import java.util.stream.Stream

@ExtendWith(MockitoExtension::class)
class AdminSpaceServiceTest {
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

    @BeforeEach
    fun setUp() {
        val instant = Instant.parse("2026-09-20T10:00:00Z")
        Mockito.lenient().`when`(clock.instant()).thenReturn(instant)
        Mockito.lenient().`when`(clock.zone).thenReturn(ZoneId.of("Asia/Seoul"))
    }

    @Test
    @DisplayName("존재하는 공간 ID로 상세 조회하면 공간을 반환하고 감사 로그를 남기지 않는다")
    fun getSpaceDetailById_success_returnsSpaceWithoutAuditLog() {
        // given
        val spaceId = 1L
        val space =
            Space
                .builder()
                .id(spaceId)
                .name("관리자용 회의실")
                .location("서울시 강남구")
                .capacity(8)
                .pricePerSlot(5000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.INACTIVE)
                .version(2)
                .build()
        given(spaceRepository.findById(spaceId)).willReturn(Optional.of(space))

        // when
        val result = adminSpaceService.getSpaceDetailById(spaceId)

        // then
        assertThat(result).isSameAs(space)
        verify(spaceRepository).findById(spaceId)
        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("존재하지 않는 공간 ID로 상세 조회하면 SPACE_NOT_FOUND 예외가 발생하고 감사 로그를 남기지 않는다")
    fun getSpaceDetailById_notFound_throwsExceptionWithoutAuditLog() {
        // given
        val spaceId = 999L
        given(spaceRepository.findById(spaceId)).willReturn(Optional.empty())

        // when & then
        assertThatThrownBy { adminSpaceService.getSpaceDetailById(spaceId) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_NOT_FOUND)

        verify(spaceRepository).findById(spaceId)
        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("관리자 공간 목록 조회 시 keyword 앞뒤 공백이 trim되어 searchSpacesByNameOrDescription에 전달된다")
    fun getSpaces_validKeyword_trimmedAndPassed() {
        // given
        val keyword = "  판교 회의실  "
        val pageable: Pageable = PageRequest.of(0, 10)
        val page: Page<Space> = PageImpl(emptyList())
        given(spaceRepository.searchSpacesByNameOrDescription(eq("판교 회의실"), eq(SpaceStatus.ACTIVE), eq(pageable)))
            .willReturn(page)

        // when
        adminSpaceService.getSpaces(SpaceStatus.ACTIVE, keyword, pageable)

        // then
        verify(spaceRepository).searchSpacesByNameOrDescription("판교 회의실", SpaceStatus.ACTIVE, pageable)
    }

    @Test
    @DisplayName("관리자 공간 목록 조회 시 keyword가 공백 문자열이면 null로 정규화된다")
    fun getSpaces_whitespaceKeyword_normalizedToNull() {
        // given
        val keyword = "   "
        val pageable: Pageable = PageRequest.of(0, 10)
        val page: Page<Space> = PageImpl(emptyList())
        given(spaceRepository.searchSpacesByNameOrDescription(isNull(), isNull(), eq(pageable)))
            .willReturn(page)

        // when
        adminSpaceService.getSpaces(null, keyword, pageable)

        // then
        verify(spaceRepository).searchSpacesByNameOrDescription(null, null, pageable)
    }

    @Test
    @DisplayName("공간 등록에 성공하면 저장된 공간 정보를 반환하고 REGISTER_SPACE 감사 로그를 기록한다")
    fun createSpace_success() {
        // given
        val adminMemberId = 100L
        val request =
            SpaceCreateRequest(
                "컨퍼런스 룸",
                "서울시 테헤란로 123",
                "최대 10인 회의실",
                10,
                5000L,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(22, 0),
            )

        val savedSpace =
            Space
                .builder()
                .id(1L)
                .name(request.name)
                .location(request.location)
                .description(request.description)
                .capacity(request.capacity)
                .pricePerSlot(request.pricePerSlot)
                .imagePath(null)
                .openingTime(request.openingTime)
                .closingTime(request.closingTime)
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build()

        given(spaceRepository.save(any<Space>())).willReturn(savedSpace)

        // when
        val response = adminSpaceService.createSpace(request, adminMemberId)

        // then
        assertThat(response.id).isEqualTo(1L)
        assertThat(response.name).isEqualTo("컨퍼런스 룸")
        assertThat(response.pricePerSlot).isEqualTo(5000L)
        assertThat(response.status).isEqualTo(SpaceStatus.ACTIVE)
        assertThat(response.version).isEqualTo(0)

        // 감사 로그 호출 및 스냅샷 검증 (ArgumentCaptor)
        val actorCaptor: ArgumentCaptor<Long> = ArgumentCaptor.forClass(Long::class.javaObjectType)
        val actionCaptor: ArgumentCaptor<AuditAction> = ArgumentCaptor.forClass(AuditAction::class.java)
        val targetTypeCaptor: ArgumentCaptor<AuditTargetType> = ArgumentCaptor.forClass(AuditTargetType::class.java)
        val targetIdCaptor: ArgumentCaptor<Long> = ArgumentCaptor.forClass(Long::class.javaObjectType)
        val beforeCaptor: ArgumentCaptor<Any> = ArgumentCaptor.forClass(Any::class.java)
        val afterCaptor: ArgumentCaptor<Any> = ArgumentCaptor.forClass(Any::class.java)

        verify(auditLogService).log(
            actorCaptor.capture(),
            actionCaptor.capture(),
            targetTypeCaptor.capture(),
            targetIdCaptor.capture(),
            beforeCaptor.capture(),
            afterCaptor.capture(),
        )

        assertThat(actorCaptor.value).isEqualTo(adminMemberId)
        assertThat(actionCaptor.value).isEqualTo(AuditAction.REGISTER_SPACE)
        assertThat(targetTypeCaptor.value).isEqualTo(AuditTargetType.SPACE)
        assertThat(targetIdCaptor.value).isEqualTo(1L)
        assertThat(beforeCaptor.value).isNull()

        assertThat(afterCaptor.value).isInstanceOf(SpaceDetailResponse::class.java)
        val afterSnapshot = afterCaptor.value as SpaceDetailResponse
        assertThat(afterSnapshot.id).isEqualTo(1L)
        assertThat(afterSnapshot.name).isEqualTo("컨퍼런스 룸")
        assertThat(afterSnapshot.pricePerSlot).isEqualTo(5000L)
        assertThat(afterSnapshot.status).isEqualTo(SpaceStatus.ACTIVE)
        assertThat(afterSnapshot.version).isEqualTo(0)
    }

    @Test
    @DisplayName("100원 단위가 아닌 요금으로 공간 등록 시 INVALID_PRICE_UNIT 예외가 발생하고 저장/감사로그가 호출되지 않는다")
    fun createSpace_invalidPriceUnit_throwsException() {
        // given
        val request =
            SpaceCreateRequest(
                "회의실",
                "강남",
                "설명",
                4,
                5250L, // 100원 단위 위반
                null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
            )

        // when & then
        assertThatThrownBy { adminSpaceService.createSpace(request, 1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PRICE_UNIT)

        verify(spaceRepository, never()).save(any<Space>())
        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("운영 시작 시각이 종료 시각과 같거나 늦으면 INVALID_OPERATING_HOURS 예외가 발생하고 저장/감사로그가 호출되지 않는다")
    fun createSpace_invalidOperatingHours_throwsException() {
        // given
        val request =
            SpaceCreateRequest(
                "회의실",
                "강남",
                "설명",
                4,
                5000L,
                null,
                LocalTime.of(18, 0), // 시작이 종료보다 늦음
                LocalTime.of(9, 0),
            )

        // when & then
        assertThatThrownBy { adminSpaceService.createSpace(request, 1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        verify(spaceRepository, never()).save(any<Space>())
        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("30분 경계가 아닌 운영시간으로 공간 등록 시 저장과 감사 로그를 남기지 않는다")
    fun createSpace_notAlignedOperatingHours_throwsException() {
        // given
        val request =
            SpaceCreateRequest(
                "회의실",
                "강남",
                "설명",
                4,
                5000L,
                null,
                LocalTime.of(9, 15),
                LocalTime.of(18, 0),
            )

        // when & then
        assertThatThrownBy { adminSpaceService.createSpace(request, 1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        verify(spaceRepository, never()).save(any<Space>())
        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("공간 수정 시 가격이 변경되면 version이 1 증가하고 MODIFY_SPACE 감사 로그에 수정 전후 스냅샷이 기록된다")
    fun updateSpace_priceChanged_versionIncrements_andLogsAudit() {
        // given
        val spaceId = 1L
        val adminMemberId = 100L

        val existingSpace =
            Space
                .builder()
                .id(spaceId)
                .name("이전 공간명")
                .location("이전 위치")
                .description("이전 설명")
                .capacity(4)
                .pricePerSlot(3000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build()

        val updateRequest =
            SpaceUpdateRequest(
                null,
                "새 공간명",
                "새 위치",
                "새 설명",
                8,
                4000L, // 가격 변경: 3000 -> 4000
                null,
                LocalTime.of(10, 0),
                LocalTime.of(20, 0),
                SpaceStatus.INACTIVE,
            )

        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existingSpace))

        // when
        val response = adminSpaceService.updateSpace(spaceId, updateRequest, adminMemberId)

        // then
        assertThat(response.name).isEqualTo("새 공간명")
        assertThat(response.capacity).isEqualTo(8)
        assertThat(response.pricePerSlot).isEqualTo(4000L)
        assertThat(response.status).isEqualTo(SpaceStatus.INACTIVE)
        assertThat(response.version).isEqualTo(1)

        // 감사 로그 호출 및 수정 전후 스냅샷 검증
        val beforeCaptor: ArgumentCaptor<Any> = ArgumentCaptor.forClass(Any::class.java)
        val afterCaptor: ArgumentCaptor<Any> = ArgumentCaptor.forClass(Any::class.java)

        verify(auditLogService).log(
            eq(adminMemberId),
            eq(AuditAction.MODIFY_SPACE),
            eq(AuditTargetType.SPACE),
            eq(spaceId),
            beforeCaptor.capture(),
            afterCaptor.capture(),
        )

        val beforeSnapshot = beforeCaptor.value as SpaceDetailResponse
        val afterSnapshot = afterCaptor.value as SpaceDetailResponse

        // before 검증
        assertThat(beforeSnapshot.name).isEqualTo("이전 공간명")
        assertThat(beforeSnapshot.pricePerSlot).isEqualTo(3000L)
        assertThat(beforeSnapshot.version).isEqualTo(0)
        assertThat(beforeSnapshot.status).isEqualTo(SpaceStatus.ACTIVE)

        // after 검증
        assertThat(afterSnapshot.name).isEqualTo("새 공간명")
        assertThat(afterSnapshot.pricePerSlot).isEqualTo(4000L)
        assertThat(afterSnapshot.version).isEqualTo(1)
        assertThat(afterSnapshot.status).isEqualTo(SpaceStatus.INACTIVE)
    }

    @Test
    @DisplayName("공간 수정 시 동일한 가격을 전달하면 version이 유지되고 감사 로그가 기록된다")
    fun updateSpace_samePrice_versionMaintained() {
        // given
        val spaceId = 1L
        val adminMemberId = 100L

        val existingSpace =
            Space
                .builder()
                .id(spaceId)
                .name("이전 공간명")
                .location("위치")
                .description("설명")
                .capacity(4)
                .pricePerSlot(3000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build()

        // 동일 가격 3000L 전달
        val updateRequest =
            SpaceUpdateRequest(
                null,
                "수정된 이름",
                null,
                null,
                null,
                3000L,
                null,
                null,
                null,
                null,
            )

        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existingSpace))

        // when
        val response = adminSpaceService.updateSpace(spaceId, updateRequest, adminMemberId)

        // then
        assertThat(response.name).isEqualTo("수정된 이름")
        assertThat(response.pricePerSlot).isEqualTo(3000L)
        assertThat(response.version).isEqualTo(0)

        val afterCaptor: ArgumentCaptor<Any> = ArgumentCaptor.forClass(Any::class.java)
        verify(auditLogService).log(
            eq(adminMemberId),
            eq(AuditAction.MODIFY_SPACE),
            eq(AuditTargetType.SPACE),
            eq(spaceId),
            any(),
            afterCaptor.capture(),
        )

        val afterSnapshot = afterCaptor.value as SpaceDetailResponse
        assertThat(afterSnapshot.version).isEqualTo(0)
    }

    @Test
    @DisplayName("존재하지 않는 spaceId 수정 시 SPACE_NOT_FOUND 예외가 발생하고 감사 로그가 호출되지 않는다")
    fun updateSpace_notFound_throwsException_andNeverLogsAudit() {
        // given
        val invalidId = 999L
        val updateRequest =
            SpaceUpdateRequest(null, "이름", "위치", "설명", 4, 3000L, null, null, null, null)

        given(spaceRepository.findByIdForUpdate(invalidId)).willReturn(Optional.empty())

        // when & then
        assertThatThrownBy { adminSpaceService.updateSpace(invalidId, updateRequest, 1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_NOT_FOUND)

        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("공간 수정 시 유효하지 않은 가격 단위가 전달되면 INVALID_PRICE_UNIT 예외가 발생하고 감사 로그가 호출되지 않는다")
    fun updateSpace_invalidPriceUnit_throwsException_andNeverLogsAudit() {
        // given
        val spaceId = 1L
        val existingSpace =
            Space
                .builder()
                .id(spaceId)
                .name("공간명")
                .location("위치")
                .capacity(4)
                .pricePerSlot(3000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build()

        val invalidRequest =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                3050L, // 100원 단위 위반
                null,
                null,
                null,
                null,
            )

        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existingSpace))

        // when & then
        assertThatThrownBy { adminSpaceService.updateSpace(spaceId, invalidRequest, 1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PRICE_UNIT)

        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("공간 수정 시 운영 시작 시각이 종료 시각과 같거나 늦으면 INVALID_OPERATING_HOURS 예외가 발생하고 감사 로그가 호출되지 않는다")
    fun updateSpace_invalidOperatingHours_throwsException_andNeverLogsAudit() {
        // given
        val spaceId = 1L
        val existingSpace =
            Space
                .builder()
                .id(spaceId)
                .name("기존 공간명")
                .location("위치")
                .capacity(4)
                .pricePerSlot(3000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build()

        val invalidRequest =
            SpaceUpdateRequest(
                null,
                "수정 시도 공간명",
                null,
                null,
                null,
                4000L,
                null,
                LocalTime.of(20, 0), // 시작이 종료보다 늦음
                LocalTime.of(10, 0),
                null,
            )

        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existingSpace))

        // when & then
        assertThatThrownBy { adminSpaceService.updateSpace(spaceId, invalidRequest, 1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("openingTime만 30분 경계가 아닌 값으로 수정하면 감사 로그를 남기지 않고 기존 운영시간을 유지한다")
    fun updateSpace_notAlignedOpeningTime_throwsException_andNeverLogsAudit() {
        // given
        val spaceId = 1L
        val existingSpace =
            Space
                .builder()
                .id(spaceId)
                .name("기존 공간명")
                .location("위치")
                .capacity(4)
                .pricePerSlot(3000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build()
        val invalidRequest =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalTime.of(9, 15),
                null,
                null,
            )

        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existingSpace))

        // when & then
        assertThatThrownBy { adminSpaceService.updateSpace(spaceId, invalidRequest, 1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_OPERATING_HOURS)

        assertThat(existingSpace.openingTime).isEqualTo(LocalTime.of(9, 0))
        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("운영시간 축소 시 미래 유효 점유 슬롯과 충돌하면 SPACE_OPERATING_HOURS_CONFLICT 예외가 발생하고 감사 로그를 남기지 않는다")
    fun updateSpace_operatingHoursConflict_throwsException() {
        // given
        val spaceId = 1L
        val existingSpace =
            Space
                .builder()
                .id(spaceId)
                .name("기존 공간")
                .location("위치")
                .capacity(4)
                .pricePerSlot(3000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(22, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build()

        // 9:00~22:00 -> 10:00~20:00으로 축소
        val shrinkRequest =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalTime.of(10, 0),
                LocalTime.of(20, 0),
                null,
            )

        val fixedNow = LocalDateTime.of(2026, 9, 20, 10, 0)
        val fixedClock = Clock.fixed(fixedNow.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"))
        given(clock.instant()).willReturn(fixedClock.instant())
        given(clock.zone).willReturn(fixedClock.zone)

        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existingSpace))
        given(
            occupiedSlotProvider.hasOccupiedSlotsOutsideHours(
                eq(spaceId),
                eq(LocalTime.of(10, 0)),
                eq(LocalTime.of(20, 0)),
                any(),
            ),
        ).willReturn(true)

        // when & then
        assertThatThrownBy { adminSpaceService.updateSpace(spaceId, shrinkRequest, 1L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_OPERATING_HOURS_CONFLICT)

        verifyNoInteractions(auditLogService)
    }

    @ParameterizedTest(name = "한쪽만 축소: {0}")
    @MethodSource("oneSidedShrinkCandidates")
    @DisplayName("오픈만 늦추거나 마감만 앞당기면 후보 전체 범위·spaceId·고정 now로 충돌을 검사하고, 충돌 시 원본 상태와 version을 보존한다")
    fun updateSpace_oneSidedShrinkConflict_preservesOriginalState(
        @Suppress("UNUSED_PARAMETER") caseName: String,
        request: SpaceUpdateRequest,
        expectedOpening: LocalTime,
        expectedClosing: LocalTime,
    ) {
        // given
        val spaceId = 1L
        val existingSpace = existingSpaceWithWideHours()
        val zone = ZoneId.of("Asia/Seoul")
        val fixedNow = LocalDateTime.of(2026, 9, 21, 10, 0)
        given(clock.instant()).willReturn(fixedNow.atZone(zone).toInstant())
        given(clock.zone).willReturn(zone)
        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existingSpace))
        given(
            occupiedSlotProvider.hasOccupiedSlotsOutsideHours(
                eq(spaceId),
                eq(expectedOpening),
                eq(expectedClosing),
                eq(fixedNow),
            ),
        ).willReturn(true)

        // when & then
        assertThatThrownBy { adminSpaceService.updateSpace(spaceId, request, 100L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_OPERATING_HOURS_CONFLICT)

        verify(spaceRepository).findByIdForUpdate(spaceId)
        verify(occupiedSlotProvider).hasOccupiedSlotsOutsideHours(
            eq(spaceId),
            eq(expectedOpening),
            eq(expectedClosing),
            eq(fixedNow),
        )
        assertOriginalWideHoursState(existingSpace)
        verify(spaceRepository, never()).save(any<Space>())
        verifyNoInteractions(auditLogService)
    }

    @ParameterizedTest(name = "한쪽만 축소·무충돌: {0}")
    @MethodSource("oneSidedShrinkCandidates")
    @DisplayName("한쪽 축소가 점유 슬롯과 충돌하지 않으면 후보 운영시간만 갱신하고 감사 로그를 남긴다")
    fun updateSpace_oneSidedShrinkWithoutConflict_updatesCandidateAndLogs(
        @Suppress("UNUSED_PARAMETER") caseName: String,
        request: SpaceUpdateRequest,
        expectedOpening: LocalTime,
        expectedClosing: LocalTime,
    ) {
        // given
        val spaceId = 1L
        val adminMemberId = 100L
        val existingSpace = existingSpaceWithWideHours()
        val zone = ZoneId.of("Asia/Seoul")
        val fixedNow = LocalDateTime.of(2026, 9, 21, 10, 0)
        given(clock.instant()).willReturn(fixedNow.atZone(zone).toInstant())
        given(clock.zone).willReturn(zone)
        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existingSpace))
        given(
            occupiedSlotProvider.hasOccupiedSlotsOutsideHours(
                eq(spaceId),
                eq(expectedOpening),
                eq(expectedClosing),
                eq(fixedNow),
            ),
        ).willReturn(false)

        // when
        val response = adminSpaceService.updateSpace(spaceId, request, adminMemberId)

        // then
        assertThat(response.openingTime).isEqualTo(expectedOpening)
        assertThat(response.closingTime).isEqualTo(expectedClosing)
        assertThat(response.name).isEqualTo("기존 공간")
        assertThat(response.imagePath).isEqualTo("/images/existing.jpg")
        assertThat(response.pricePerSlot).isEqualTo(3000L)
        assertThat(response.version).isEqualTo(3)

        verify(occupiedSlotProvider).hasOccupiedSlotsOutsideHours(
            eq(spaceId),
            eq(expectedOpening),
            eq(expectedClosing),
            eq(fixedNow),
        )
        verify(auditLogService).log(
            eq(adminMemberId),
            eq(AuditAction.MODIFY_SPACE),
            eq(AuditTargetType.SPACE),
            eq(spaceId),
            any(),
            any(),
        )
    }

    @ParameterizedTest(name = "운영시간 확장: {0}")
    @MethodSource("expansionCandidates")
    @DisplayName("운영시간이 축소되지 않으면 충돌 조회 없이 갱신과 감사 로그를 수행한다")
    fun updateSpace_expandedHours_skipsConflictQuery(
        @Suppress("UNUSED_PARAMETER") caseName: String,
        request: SpaceUpdateRequest,
        expectedOpening: LocalTime,
        expectedClosing: LocalTime,
    ) {
        // given
        val spaceId = 1L
        val adminMemberId = 100L
        val existingSpace = existingSpaceWithWideHours()
        given(spaceRepository.findByIdForUpdate(spaceId)).willReturn(Optional.of(existingSpace))

        // when (확장·유지 경로는 clock을 사용하지 않으므로 고정 stubbing을 추가하지 않는다)
        val response = adminSpaceService.updateSpace(spaceId, request, adminMemberId)

        // then
        assertThat(response.openingTime).isEqualTo(expectedOpening)
        assertThat(response.closingTime).isEqualTo(expectedClosing)
        assertThat(response.name).isEqualTo("기존 공간")
        assertThat(response.version).isEqualTo(3)

        verify(occupiedSlotProvider, never()).hasOccupiedSlotsOutsideHours(any(), any(), any(), any())
        verify(auditLogService).log(
            eq(adminMemberId),
            eq(AuditAction.MODIFY_SPACE),
            eq(AuditTargetType.SPACE),
            eq(spaceId),
            any(),
            any(),
        )
    }

    private fun existingSpaceWithWideHours(): Space =
        Space
            .builder()
            .id(1L)
            .name("기존 공간")
            .location("기존 위치")
            .description("기존 설명")
            .capacity(4)
            .pricePerSlot(3000L)
            .imagePath("/images/existing.jpg")
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(22, 0))
            .status(SpaceStatus.ACTIVE)
            .version(3)
            .build()

    private fun assertOriginalWideHoursState(space: Space) {
        assertThat(space.id).isEqualTo(1L)
        assertThat(space.name).isEqualTo("기존 공간")
        assertThat(space.location).isEqualTo("기존 위치")
        assertThat(space.description).isEqualTo("기존 설명")
        assertThat(space.capacity).isEqualTo(4)
        assertThat(space.pricePerSlot).isEqualTo(3000L)
        assertThat(space.imagePath).isEqualTo("/images/existing.jpg")
        assertThat(space.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(space.closingTime).isEqualTo(LocalTime.of(22, 0))
        assertThat(space.status).isEqualTo(SpaceStatus.ACTIVE)
        assertThat(space.version).isEqualTo(3)
    }

    companion object {
        @JvmStatic
        fun oneSidedShrinkCandidates(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "오픈만 09:00 -> 10:00",
                    SpaceUpdateRequest(null, null, null, null, null, null, null, LocalTime.of(10, 0), null, null),
                    LocalTime.of(10, 0),
                    LocalTime.of(22, 0),
                ),
                Arguments.of(
                    "마감만 22:00 -> 20:00",
                    SpaceUpdateRequest(null, null, null, null, null, null, null, null, LocalTime.of(20, 0), null),
                    LocalTime.of(9, 0),
                    LocalTime.of(20, 0),
                ),
            )

        @JvmStatic
        fun expansionCandidates(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "오픈만 09:00 -> 08:00",
                    SpaceUpdateRequest(null, null, null, null, null, null, null, LocalTime.of(8, 0), null, null),
                    LocalTime.of(8, 0),
                    LocalTime.of(22, 0),
                ),
                Arguments.of(
                    "마감만 22:00 -> 23:00",
                    SpaceUpdateRequest(null, null, null, null, null, null, null, null, LocalTime.of(23, 0), null),
                    LocalTime.of(9, 0),
                    LocalTime.of(23, 0),
                ),
            )
    }
}
