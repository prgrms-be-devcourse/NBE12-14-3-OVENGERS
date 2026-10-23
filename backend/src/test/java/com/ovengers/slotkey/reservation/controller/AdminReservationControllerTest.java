package com.ovengers.slotkey.reservation.controller;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ovengers.slotkey.global.error.GlobalExceptionHandler;
import com.ovengers.slotkey.reservation.dto.request.AdminReservationSearchCondition;
import com.ovengers.slotkey.reservation.dto.response.AdminReservationResponse;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.service.AdminReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminReservationControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AdminReservationService adminReservationService;

    @InjectMocks
    private AdminReservationController adminReservationController;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders.standaloneSetup(adminReservationController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/admin/reservations 기본 요청 시 200 OK와 PageResponse 규격(content, page, size, totalElements, totalPages)을 반환한다")
    void getReservations_default_returnsPageResponse() throws Exception {
        // given
        AdminReservationResponse item = new AdminReservationResponse(
                100L,
                1L,
                "user@example.com",
                10L,
                "대회의실 A",
                LocalDateTime.of(2026, 9, 30, 10, 0),
                LocalDateTime.of(2026, 9, 30, 12, 0),
                ReservationStatus.CONFIRMED,
                20000,
                LocalDateTime.of(2026, 9, 29, 15, 0)
        );
        Page<AdminReservationResponse> page = new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1);
        given(adminReservationService.searchReservations(any(AdminReservationSearchCondition.class), any(Pageable.class)))
                .willReturn(page);

        // when & then
        mockMvc.perform(get("/api/v1/admin/reservations")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].reservationId").value(100L))
                .andExpect(jsonPath("$.data.content[0].memberId").value(1L))
                .andExpect(jsonPath("$.data.content[0].memberEmail").value("user@example.com"))
                .andExpect(jsonPath("$.data.content[0].spaceId").value(10L))
                .andExpect(jsonPath("$.data.content[0].spaceName").value("대회의실 A"))
                .andExpect(jsonPath("$.data.content[0].startTime").value("2026-09-30T10:00:00"))
                .andExpect(jsonPath("$.data.content[0].endTime").value("2026-09-30T12:00:00"))
                .andExpect(jsonPath("$.data.content[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.content[0].totalAmount").value(20000))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(adminReservationService).searchReservations(any(AdminReservationSearchCondition.class), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("date, spaceId, status 필터와 페이징 파라미터가 SearchCondition과 Pageable로 정확하게 매핑된다")
    void getReservations_withFilters_bindsCorrectly() throws Exception {
        // given
        Page<AdminReservationResponse> emptyPage = new PageImpl<>(List.of(), PageRequest.of(1, 10), 0);
        given(adminReservationService.searchReservations(any(AdminReservationSearchCondition.class), any(Pageable.class)))
                .willReturn(emptyPage);

        // when & then
        mockMvc.perform(get("/api/v1/admin/reservations")
                        .param("date", "2026-09-30")
                        .param("spaceId", "5")
                        .param("status", "CONFIRMED")
                        .param("page", "1")
                        .param("size", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10));

        ArgumentCaptor<AdminReservationSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(AdminReservationSearchCondition.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        verify(adminReservationService).searchReservations(conditionCaptor.capture(), pageableCaptor.capture());

        AdminReservationSearchCondition captured = conditionCaptor.getValue();
        assertThat(captured.date()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(captured.spaceId()).isEqualTo(5L);
        assertThat(captured.status()).isEqualTo(ReservationStatus.CONFIRMED);

        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("잘못된 날짜 형식이 전달되면 400 Bad Request와 VALIDATION_FAILED를 반환한다")
    void getReservations_invalidDateFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reservations")
                        .param("date", "invalid-date")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(adminReservationService);
    }

    @Test
    @DisplayName("잘못된 enum 상태가 전달되면 400 Bad Request와 VALIDATION_FAILED를 반환한다")
    void getReservations_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reservations")
                        .param("status", "NOT_A_STATUS")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(adminReservationService);
    }

    @Test
    @DisplayName("spaceId가 0 이하(음수 또는 0)이면 400 Bad Request와 VALIDATION_FAILED를 반환한다")
    void getReservations_invalidSpaceId_returns400() throws Exception {
        // spaceId = 0
        mockMvc.perform(get("/api/v1/admin/reservations")
                        .param("spaceId", "0")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // spaceId = -1
        mockMvc.perform(get("/api/v1/admin/reservations")
                        .param("spaceId", "-1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(adminReservationService);
    }

    @Test
    @DisplayName("존재하지 않는 양수 spaceId로 조회 시 200 OK와 빈 PageResponse(totalElements=0, content=[])를 반환한다")
    void getReservations_nonExistentSpaceId_returnsEmpty200() throws Exception {
        // given
        Page<AdminReservationResponse> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        given(adminReservationService.searchReservations(any(AdminReservationSearchCondition.class), any(Pageable.class)))
                .willReturn(emptyPage);

        // when & then
        mockMvc.perform(get("/api/v1/admin/reservations")
                        .param("spaceId", "999999")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }
}
