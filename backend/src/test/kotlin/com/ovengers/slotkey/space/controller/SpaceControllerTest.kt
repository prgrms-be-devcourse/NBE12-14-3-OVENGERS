package com.ovengers.slotkey.space.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.error.GlobalExceptionHandler
import com.ovengers.slotkey.space.dto.request.SpaceSearchCondition
import com.ovengers.slotkey.space.dto.response.SlotResponse
import com.ovengers.slotkey.space.dto.response.SpaceSlotAvailabilityResponse
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.space.service.OccupiedSlotProvider
import com.ovengers.slotkey.space.service.SpaceQueryService
import com.ovengers.slotkey.space.service.SpaceSlotAvailabilityService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
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
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@ExtendWith(MockitoExtension::class)
class SpaceControllerTest {
    private lateinit var mockMvc: MockMvc
    private lateinit var realServiceMockMvc: MockMvc

    @Mock
    private lateinit var spaceQueryService: SpaceQueryService

    @Mock
    private lateinit var slotAvailabilityService: SpaceSlotAvailabilityService

    @Mock
    private lateinit var mockSpaceRepository: SpaceRepository

    @Mock
    private lateinit var mockOccupiedSlotProvider: OccupiedSlotProvider

    @InjectMocks
    private lateinit var spaceController: SpaceController

    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper()
        objectMapper.registerModule(JavaTimeModule())

        mockMvc =
            MockMvcBuilders
                .standaloneSetup(spaceController)
                .setCustomArgumentResolvers(PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(GlobalExceptionHandler())
                .build()

        val realSpaceQueryService =
            SpaceQueryService(
                mockSpaceRepository,
                mockOccupiedSlotProvider,
                Clock.systemDefaultZone(),
            )
        val realServiceController = SpaceController(realSpaceQueryService, slotAvailabilityService)
        realServiceMockMvc =
            MockMvcBuilders
                .standaloneSetup(realServiceController)
                .setCustomArgumentResolvers(PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    private fun createSpace(
        id: Long,
        name: String,
    ): Space =
        Space
            .builder()
            .id(id)
            .name(name)
            .location("서울시 강남구 테헤란로")
            .description("회의실 설명")
            .capacity(6)
            .pricePerSlot(5000L)
            .imagePath("/img/room.jpg")
            .openingTime(LocalTime.of(9, 0))
            .closingTime(LocalTime.of(18, 0))
            .status(SpaceStatus.ACTIVE)
            .version(1)
            .build()

    @Test
    @DisplayName("GET /api/v1/spaces 기본 요청 시 200 OK와 PageResponse 규격의 공간 목록을 반환한다")
    fun getSpacesPage_default_returnsPageResponse() {
        // given
        val pageable: Pageable = PageRequest.of(0, 20)
        val spaces = listOf(createSpace(1L, "회의실 A"), createSpace(2L, "회의실 B"))
        val page: Page<Space> = PageImpl(spaces, pageable, spaces.size.toLong())

        val expectedCondition =
            SpaceSearchCondition(null, null, null, null, null, null, null)
        given(spaceQueryService.getSpacesPage(any(Pageable::class.java), eq(expectedCondition))).willReturn(page)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/spaces")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.content.length()").value(2))
            .andExpect(jsonPath("$.data.content[0].id").value(1L))
            .andExpect(jsonPath("$.data.content[0].name").value("회의실 A"))
            .andExpect(jsonPath("$.data.content[1].id").value(2L))
            .andExpect(jsonPath("$.data.content[1].name").value("회의실 B"))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.totalPages").value(1))

        val pageableCaptor = ArgumentCaptor.forClass(Pageable::class.java)
        verify(spaceQueryService).getSpacesPage(pageableCaptor.capture(), eq(expectedCondition))
        assertThat(pageableCaptor.value.pageSize).isEqualTo(20)
    }

    @Test
    @DisplayName("GET /api/v1/spaces 요청 시 keyword, location, 가격 범위, page, size 파라미터가 서비스에 올바르게 전달된다")
    fun getSpacesPage_withFiltersAndPaging_success() {
        // given
        val pageable: Pageable = PageRequest.of(1, 10)
        val spaces =
            listOf(
                createSpace(3L, "강남 회의실 A"),
                createSpace(4L, "강남 회의실 B"),
                createSpace(5L, "강남 회의실 C"),
                createSpace(6L, "강남 회의실 D"),
                createSpace(7L, "강남 회의실 E"),
            )
        val page: Page<Space> = PageImpl(spaces, pageable, 15L)

        val expectedCondition =
            SpaceSearchCondition("강남", "강남", 3000L, 8000L, null, null, null)
        given(spaceQueryService.getSpacesPage(any(Pageable::class.java), eq(expectedCondition))).willReturn(page)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/spaces")
                    .param("keyword", "강남")
                    .param("location", "강남")
                    .param("minPrice", "3000")
                    .param("maxPrice", "8000")
                    .param("page", "1")
                    .param("size", "10")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.content.length()").value(5))
            .andExpect(jsonPath("$.data.content[0].name").value("강남 회의실 A"))
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(10))
            .andExpect(jsonPath("$.data.totalElements").value(15))
            .andExpect(jsonPath("$.data.totalPages").value(2))

        val pageableCaptor = ArgumentCaptor.forClass(Pageable::class.java)
        verify(spaceQueryService).getSpacesPage(pageableCaptor.capture(), eq(expectedCondition))
        assertThat(pageableCaptor.value.pageNumber).isEqualTo(1)
        assertThat(pageableCaptor.value.pageSize).isEqualTo(10)
    }

    @Test
    @DisplayName("GET /api/v1/spaces 요청 시 date/startTime/endTime이 서비스에 올바르게 전달된다")
    fun getSpacesPage_withTimeFilter_success() {
        // given
        val pageable: Pageable = PageRequest.of(0, 20)
        val spaces = listOf(createSpace(1L, "회의실 A"))
        val page: Page<Space> = PageImpl(spaces, pageable, spaces.size.toLong())

        val expectedCondition =
            SpaceSearchCondition(
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 9, 23),
                LocalTime.of(14, 0),
                LocalTime.of(15, 0),
            )
        given(spaceQueryService.getSpacesPage(any(Pageable::class.java), eq(expectedCondition))).willReturn(page)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/spaces")
                    .param("date", "2026-09-23")
                    .param("startTime", "14:00:00")
                    .param("endTime", "15:00:00")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content.length()").value(1))

        verify(spaceQueryService).getSpacesPage(any(Pageable::class.java), eq(expectedCondition))
    }

    @Test
    @DisplayName("GET /api/v1/spaces/{id} 성공 시 200 OK와 공간 상세 정보(version 포함)를 반환한다")
    fun getSpaceDetailById_success() {
        // given
        val spaceId = 1L
        val space = createSpace(spaceId, "회의실 A")
        given(spaceQueryService.getSpaceDetailById(spaceId)).willReturn(space)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/spaces/{spaceId}", spaceId)
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.id").value(spaceId))
            .andExpect(jsonPath("$.data.name").value("회의실 A"))
            .andExpect(jsonPath("$.data.location").value("서울시 강남구 테헤란로"))
            .andExpect(jsonPath("$.data.capacity").value(6))
            .andExpect(jsonPath("$.data.pricePerSlot").value(5000L))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))

        verify(spaceQueryService).getSpaceDetailById(spaceId)
    }

    @Test
    @DisplayName("GET /api/v1/spaces/{id} 미존재 공간 조회 시 404 Not Found와 SPACE_NOT_FOUND 에러를 반환한다")
    fun getSpaceDetailById_notFound() {
        // given
        val notFoundId = 999L
        given(spaceQueryService.getSpaceDetailById(notFoundId))
            .willThrow(BusinessException(ErrorCode.SPACE_NOT_FOUND))

        // when & then
        mockMvc
            .perform(
                get("/api/v1/spaces/{spaceId}", notFoundId)
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"))

        verify(spaceQueryService).getSpaceDetailById(notFoundId)
    }

    @Test
    @DisplayName("GET /api/v1/spaces/{id}/slots 조회 시 200 OK와 슬롯 가용성 목록을 반환한다")
    fun getSlotAvailability_success() {
        // given
        val spaceId = 1L
        val date = LocalDate.of(2026, 9, 20)

        val slots =
            listOf(
                SlotResponse.of(
                    LocalDateTime.of(2026, 9, 20, 9, 0),
                    LocalDateTime.of(2026, 9, 20, 9, 30),
                    false,
                ),
                SlotResponse.of(
                    LocalDateTime.of(2026, 9, 20, 9, 30),
                    LocalDateTime.of(2026, 9, 20, 10, 0),
                    true,
                ),
            )

        val response = SpaceSlotAvailabilityResponse.of(spaceId, date, slots)
        given(slotAvailabilityService.getSlotAvailability(spaceId, date)).willReturn(response)

        // when & then
        mockMvc
            .perform(
                get("/api/v1/spaces/{spaceId}/slots", spaceId)
                    .param("date", "2026-09-20")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.spaceId").value(spaceId))
            .andExpect(jsonPath("$.data.date").value("2026-09-20"))
            .andExpect(jsonPath("$.data.slots").isArray())
            .andExpect(jsonPath("$.data.slots.length()").value(2))
            .andExpect(jsonPath("$.data.slots[0].slotStart").value("2026-09-20T09:00:00"))
            .andExpect(jsonPath("$.data.slots[0].slotEnd").value("2026-09-20T09:30:00"))
            .andExpect(jsonPath("$.data.slots[0].isAvailable").value(false))
            .andExpect(jsonPath("$.data.slots[1].slotStart").value("2026-09-20T09:30:00"))
            .andExpect(jsonPath("$.data.slots[1].slotEnd").value("2026-09-20T10:00:00"))
            .andExpect(jsonPath("$.data.slots[1].isAvailable").value(true))

        verify(slotAvailabilityService).getSlotAvailability(spaceId, date)
    }

    @Test
    @DisplayName("GET /api/v1/spaces minPrice 음수 요청 시 실제 서비스 검증을 거쳐 400 Bad Request와 VALIDATION_FAILED 에러를 반환한다")
    fun getSpacesPage_negativeMinPrice_returns400ValidationFailed() {
        // when & then
        realServiceMockMvc
            .perform(
                get("/api/v1/spaces")
                    .param("minPrice", "-1")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(mockSpaceRepository, mockOccupiedSlotProvider)
    }

    @Test
    @DisplayName("GET /api/v1/spaces minPrice > maxPrice 요청 시 실제 서비스 검증을 거쳐 400 Bad Request와 VALIDATION_FAILED 에러를 반환한다")
    fun getSpacesPage_invertedPriceRange_returns400ValidationFailed() {
        // when & then
        realServiceMockMvc
            .perform(
                get("/api/v1/spaces")
                    .param("minPrice", "10000")
                    .param("maxPrice", "5000")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(mockSpaceRepository, mockOccupiedSlotProvider)
    }

    @Test
    @DisplayName("GET /api/v1/spaces 부분 시간 입력(date만 입력) 요청 시 실제 서비스 검증을 거쳐 400 Bad Request와 VALIDATION_FAILED 에러를 반환한다")
    fun getSpacesPage_partialTimeFilter_returns400ValidationFailed() {
        // when & then
        realServiceMockMvc
            .perform(
                get("/api/v1/spaces")
                    .param("date", "2026-10-01")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(mockSpaceRepository, mockOccupiedSlotProvider)
    }

    @Test
    @DisplayName("GET /api/v1/spaces 30분 단위 위반 요청 시 실제 서비스 검증을 거쳐 400 Bad Request와 VALIDATION_FAILED 에러를 반환한다")
    fun getSpacesPage_nonAlignedTime_returns400ValidationFailed() {
        // when & then
        realServiceMockMvc
            .perform(
                get("/api/v1/spaces")
                    .param("date", "2026-10-01")
                    .param("startTime", "10:15:00")
                    .param("endTime", "11:00:00")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(mockSpaceRepository, mockOccupiedSlotProvider)
    }

    @Test
    @DisplayName("GET /api/v1/spaces 나노초 단위 포함 요청 시 실제 서비스 검증을 거쳐 400 Bad Request와 VALIDATION_FAILED 에러를 반환한다")
    fun getSpacesPage_nanoTime_returns400ValidationFailed() {
        // when & then
        realServiceMockMvc
            .perform(
                get("/api/v1/spaces")
                    .param("date", "2026-10-01")
                    .param("startTime", "10:00:00.500")
                    .param("endTime", "11:00:00")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(mockSpaceRepository, mockOccupiedSlotProvider)
    }

    @Test
    @DisplayName("GET /api/v1/spaces 시간 역전 요청 시 실제 서비스 검증을 거쳐 400 Bad Request와 INVALID_TIME_RANGE 에러를 반환한다")
    fun getSpacesPage_invertedTimeRange_returns400InvalidTimeRange() {
        // when & then
        realServiceMockMvc
            .perform(
                get("/api/v1/spaces")
                    .param("date", "2026-10-01")
                    .param("startTime", "11:00:00")
                    .param("endTime", "10:00:00")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"))

        verifyNoInteractions(mockSpaceRepository, mockOccupiedSlotProvider)
    }

    @Test
    @DisplayName("GET /api/v1/spaces 날짜 형식 파싱 오류 시 GlobalExceptionHandler를 통해 400 Bad Request와 VALIDATION_FAILED 에러를 반환한다")
    fun getSpacesPage_invalidDateFormat_returns400ValidationFailed() {
        // when & then
        realServiceMockMvc
            .perform(
                get("/api/v1/spaces")
                    .param("date", "invalid-date")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))

        verifyNoInteractions(mockSpaceRepository, mockOccupiedSlotProvider)
    }

    @Test
    @DisplayName("GET /api/v1/spaces minPrice=0&maxPrice=0 요청 시 실제 서비스를 통과하여 200 OK를 반환한다")
    fun getSpacesPage_zeroPrices_passesServiceValidationAndReturns200() {
        // given
        val emptyPage: Page<Space> = PageImpl<Space>(emptyList(), PageRequest.of(0, 10), 0L)
        given(
            mockSpaceRepository.searchSpaces(
                eq(SpaceStatus.ACTIVE),
                any(),
                any(),
                eq(0L),
                eq(0L),
                any(),
                any(),
                any(),
                any(),
            ),
        ).willReturn(emptyPage)

        // when & then
        realServiceMockMvc
            .perform(
                get("/api/v1/spaces")
                    .param("minPrice", "0")
                    .param("maxPrice", "0")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.totalElements").value(0))
    }
}
