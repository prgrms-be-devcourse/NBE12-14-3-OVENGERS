package com.ovengers.slotkey.reservation.service;


import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.entity.MemberRole;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.reservation.dto.request.AdminReservationSearchCondition;
import com.ovengers.slotkey.reservation.dto.response.AdminReservationResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import com.ovengers.slotkey.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminReservationSearchIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private AdminReservationService adminReservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private SpaceRepository spaceRepository;

    @Autowired
    private MemberRepository memberRepository;

    private Long spaceId1;
    private Long spaceId2;
    private Long memberId;

    private final LocalDate day1 = LocalDate.of(2026, 9, 29);
    private final LocalDate day2 = LocalDate.of(2026, 9, 30);
    private final LocalDate day3 = LocalDate.of(2026, 10, 1);

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAllInBatch();
        spaceRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();

        Member member = memberRepository.save(new Member(
                "tester@slotkey.test",
                "예약테스터"
        ));
        memberId = member.getId();

        Space space1 = spaceRepository.save(Space.builder()
                .name("회의실 A")
                .location("서울 강남")
                .description("설명 A")
                .pricePerSlot(5000L)
                .capacity(10)
                .openingTime(LocalTime.of(8, 0))
                .closingTime(LocalTime.of(22, 0))
                .status(SpaceStatus.ACTIVE)
                .build());
        spaceId1 = space1.getId();

        Space space2 = spaceRepository.save(Space.builder()
                .name("세미나실 B")
                .location("경기 판교")
                .description("설명 B")
                .pricePerSlot(10000L)
                .capacity(30)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(23, 0))
                .status(SpaceStatus.ACTIVE)
                .build());
        spaceId2 = space2.getId();

        List<Reservation> reservations = new ArrayList<>();

        // day1, space1, CONFIRMED: 5건
        for (int i = 0; i < 5; i++) {
            reservations.add(createReservation(spaceId1, day1.atTime(9 + i, 0), day1.atTime(10 + i, 0), ReservationStatus.CONFIRMED));
        }
        // day1, space2, HELD: 5건
        for (int i = 0; i < 5; i++) {
            reservations.add(createReservation(spaceId2, day1.atTime(14 + i, 0), day1.atTime(15 + i, 0), ReservationStatus.HELD));
        }

        // day2 경계값 1: 00:00:00 정각 시작 (day2 포함 대상)
        reservations.add(createReservation(spaceId1, day2.atTime(0, 0, 0), day2.atTime(1, 0, 0), ReservationStatus.CONFIRMED));

        // day2, space1, CONFIRMED: 추가 4건 (총 5건)
        for (int i = 1; i <= 4; i++) {
            reservations.add(createReservation(spaceId1, day2.atTime(9 + i, 0), day2.atTime(10 + i, 0), ReservationStatus.CONFIRMED));
        }

        // day2, space1, IN_USE: 4건
        for (int i = 0; i < 4; i++) {
            reservations.add(createReservation(spaceId1, day2.atTime(15 + i, 0), day2.atTime(16 + i, 0), ReservationStatus.IN_USE));
        }

        // day2, space2, CANCELLED: 3건
        for (int i = 0; i < 3; i++) {
            reservations.add(createReservation(spaceId2, day2.atTime(10 + i, 0), day2.atTime(11 + i, 0), ReservationStatus.CANCELLED));
        }

        // day3 경계값: 다음날 00:00:00 시작 (day2 검색 시 제외되어야 함)
        reservations.add(createReservation(spaceId2, day3.atTime(0, 0, 0), day3.atTime(1, 0, 0), ReservationStatus.CONFIRMED));

        // 총 5 + 5 + 1 + 4 + 4 + 3 + 1 = 23건 저장
        reservationRepository.saveAll(reservations);
    }

    private Reservation createReservation(Long spaceId, LocalDateTime start, LocalDateTime end, ReservationStatus status) {
        Reservation.ReservationBuilder builder = Reservation.builder()
                .memberId(memberId)
                .spaceId(spaceId)
                .startTime(start)
                .endTime(end)
                .status(status)
                .pricePerSlotSnapshot(5000)
                .totalAmount(10000)
                .createdAt(start.minusDays(1));

        if (status == ReservationStatus.HELD) {
            builder.holdExpiresAt(start.plusMinutes(10));
        } else if (status == ReservationStatus.CANCELLED) {
            builder.cancelledAt(start.minusHours(1));
        } else if (status == ReservationStatus.IN_USE) {
            builder.checkedInAt(start.plusMinutes(5));
        } else if (status == ReservationStatus.COMPLETED) {
            builder.checkedInAt(start.plusMinutes(5));
            builder.checkedOutAt(end);
        }

        return builder.build();
    }

    @Test
    @DisplayName("무조건 조회: 전체 23건 조회, totalElements=23, totalPages=2, 기본 정렬 id DESC")
    void searchReservations_noCondition_returnsAllWithDefaultSort() {
        AdminReservationSearchCondition condition = new AdminReservationSearchCondition(null, null, null);
        Page<AdminReservationResponse> page0 = adminReservationService.searchReservations(condition, PageRequest.of(0, 20));

        assertThat(page0.getTotalElements()).isEqualTo(23);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.getContent()).hasSize(20);

        // 기본 정렬 id DESC 검증
        List<AdminReservationResponse> content = page0.getContent();
        for (int i = 0; i < content.size() - 1; i++) {
            assertThat(content.get(i).reservationId()).isGreaterThan(content.get(i + 1).reservationId());
        }

        // 2페이지 조회
        Page<AdminReservationResponse> page1 = adminReservationService.searchReservations(condition, PageRequest.of(1, 20));
        assertThat(page1.getContent()).hasSize(3);
        assertThat(page0.getContent().get(19).reservationId()).isGreaterThan(page1.getContent().get(0).reservationId());
    }

    @Test
    @DisplayName("date 단독 필터: 이용 시작일 [00:00, 24:00) 범위만 조회되며 경계 시각(00:00 포함, 다음날 00:00 제외)이 정확하다")
    void searchReservations_dateFilter_boundaryCheck() {
        // day2 (2026-09-30): 경계 00:00 1건 + CONFIRMED 4건 + IN_USE 4건 + CANCELLED 3건 = 12건
        AdminReservationSearchCondition condition = new AdminReservationSearchCondition(day2, null, null);
        Page<AdminReservationResponse> result = adminReservationService.searchReservations(condition, PageRequest.of(0, 50));

        assertThat(result.getTotalElements()).isEqualTo(12);
        assertThat(result.getContent()).allSatisfy(r -> {
            assertThat(r.startTime()).isAfterOrEqualTo(day2.atStartOfDay());
            assertThat(r.startTime()).isBefore(day3.atStartOfDay());
        });

        // 00:00:00 정각 예약 포함 확인
        boolean hasExactMidnight = result.getContent().stream()
                .anyMatch(r -> r.startTime().equals(day2.atStartOfDay()));
        assertThat(hasExactMidnight).isTrue();

        // day3 00:00:00 예약 제외 확인
        boolean hasNextDayMidnight = result.getContent().stream()
                .anyMatch(r -> r.startTime().equals(day3.atStartOfDay()));
        assertThat(hasNextDayMidnight).isFalse();
    }

    @Test
    @DisplayName("spaceId 단독 필터: 지정된 spaceId의 예약만 반환한다")
    void searchReservations_spaceIdFilter() {
        // spaceId1: day1(5) + day2(1+4+4) = 14건
        AdminReservationSearchCondition condition = new AdminReservationSearchCondition(null, spaceId1, null);
        Page<AdminReservationResponse> result = adminReservationService.searchReservations(condition, PageRequest.of(0, 50));

        assertThat(result.getTotalElements()).isEqualTo(14);
        assertThat(result.getContent()).allSatisfy(r -> assertThat(r.spaceId()).isEqualTo(spaceId1));
    }

    @Test
    @DisplayName("status 단독 필터: 지정된 status의 예약만 반환한다")
    void searchReservations_statusFilter() {
        // CONFIRMED: day1(5) + day2(1+4) + day3(1) = 11건
        AdminReservationSearchCondition condition = new AdminReservationSearchCondition(null, null, ReservationStatus.CONFIRMED);
        Page<AdminReservationResponse> result = adminReservationService.searchReservations(condition, PageRequest.of(0, 50));

        assertThat(result.getTotalElements()).isEqualTo(11);
        assertThat(result.getContent()).allSatisfy(r -> assertThat(r.status()).isEqualTo(ReservationStatus.CONFIRMED));
    }

    @Test
    @DisplayName("복합 필터: date + spaceId + status 조합 시 모든 조건을 만족하는 예약만 반환한다")
    void searchReservations_compositeFilter() {
        // day2 AND spaceId1 AND CONFIRMED: 00:00(1) + 4건 = 5건
        AdminReservationSearchCondition condition = new AdminReservationSearchCondition(
                day2, spaceId1, ReservationStatus.CONFIRMED
        );
        Page<AdminReservationResponse> result = adminReservationService.searchReservations(condition, PageRequest.of(0, 50));

        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getContent()).allSatisfy(r -> {
            assertThat(r.startTime()).isAfterOrEqualTo(day2.atStartOfDay());
            assertThat(r.startTime()).isBefore(day3.atStartOfDay());
            assertThat(r.spaceId()).isEqualTo(spaceId1);
            assertThat(r.status()).isEqualTo(ReservationStatus.CONFIRMED);
        });
    }

    @Test
    @DisplayName("존재하지 않는 양수 spaceId로 검색 시 예외 없이 빈 200 페이지(totalElements=0)를 반환한다")
    void searchReservations_nonExistentSpaceId_returnsEmptyPage() {
        AdminReservationSearchCondition condition = new AdminReservationSearchCondition(null, 999999L, null);
        Page<AdminReservationResponse> result = adminReservationService.searchReservations(condition, PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(0);
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("클라이언트 지정 정렬: sort=id,asc 지정 시 오름차순으로 정렬된다")
    void searchReservations_clientSort_returnsAscendingOrder() {
        AdminReservationSearchCondition condition = new AdminReservationSearchCondition(null, null, null);
        Page<AdminReservationResponse> result = adminReservationService.searchReservations(
                condition,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "id"))
        );

        List<AdminReservationResponse> content = result.getContent();
        for (int i = 0; i < content.size() - 1; i++) {
            assertThat(content.get(i).reservationId()).isLessThan(content.get(i + 1).reservationId());
        }
    }
}
