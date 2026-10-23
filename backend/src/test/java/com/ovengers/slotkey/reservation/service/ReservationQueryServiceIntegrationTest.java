package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.reservation.dto.request.ReservationSearchCondition;
import com.ovengers.slotkey.reservation.dto.response.ReservationListResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import com.ovengers.slotkey.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ReservationQueryServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private ReservationQueryService reservationQueryService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationStatusHistoryRepository reservationStatusHistoryRepository;

    @Autowired
    private SpaceRepository spaceRepository;

    @Autowired
    private MemberRepository memberRepository;

    private Member memberA;
    private Member memberB;
    private Member memberC;
    private Member admin;

    private Space spaceS1;
    private Space spaceS2;
    private Space spaceS3;
    private Space spaceS4;

    private final Map<String, Reservation> resA = new LinkedHashMap<>();
    private final Map<String, Reservation> resB = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        reservationStatusHistoryRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        spaceRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
        resA.clear();
        resB.clear();

        // 1. 회원 저장
        memberA = memberRepository.save(new Member("memberA@slotkey.test",  "회원A"));
        memberB = memberRepository.save(new Member("memberB@slotkey.test",  "회원B"));
        memberC = memberRepository.save(new Member("memberC@slotkey.test",  "회원C"));
        admin = memberRepository.save(new Member("admin@slotkey.test",  "관리자"));

        // 2. 공간 저장 (S1 ~ S4)
        spaceS1 = spaceRepository.save(Space.builder()
                .name("판교 회의실")
                .location("성남시 분당구")
                .description("집중하기 좋은 쾌적한 회의실")
                .imagePath("/images/pangyo-meeting.jpg")
                .capacity(4)
                .pricePerSlot(5000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(1)
                .build());

        spaceS2 = spaceRepository.save(Space.builder()
                .name("강남 라운지")
                .location("서울시 강남구")
                .description("라운지 공간")
                .imagePath("/images/gangnam-lounge.jpg")
                .capacity(10)
                .pricePerSlot(10000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(1)
                .build());

        spaceS3 = spaceRepository.save(Space.builder()
                .name("야간 판교 오피스")
                .location("성남시 분당구")
                .description("야간 작업 전용")
                .capacity(6)
                .pricePerSlot(7000L)
                .openingTime(LocalTime.of(18, 0))
                .closingTime(LocalTime.of(23, 0))
                .status(SpaceStatus.INACTIVE) // 비활성 공간
                .version(1)
                .build());

        spaceS4 = spaceRepository.save(Space.builder()
                .name("하남 스튜디오")
                .location("성남시 판교역로") // 위치에만 '판교'
                .description("판교 테크팀 추천 대형 스튜디오") // 설명에만 '판교'
                .capacity(8)
                .pricePerSlot(6000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(1)
                .build());

        // 3. 예약 데이터 생성 (순번이 커질수록 startTime이 늦음, A11과 A12만 같은 startTime)
        LocalDateTime now = LocalDateTime.now();

        // A01~A10 (S1, CONFIRMED)
        for (int i = 1; i <= 10; i++) {
            String label = String.format("A%02d", i);
            LocalDateTime start = LocalDateTime.of(2026, 10, 1, 8, 0).plusHours(i);
            resA.put(label, saveReservation(memberA.getId(), spaceS1.getId(), start, start.plusHours(1),
                    ReservationStatus.CONFIRMED, null, null, null, now));
        }

        // A11, A12: 같은 startTime (2026-10-02 11:00:00). A를 순번 순서로 저장하여 A12.id > A11.id
        LocalDateTime sameStart = LocalDateTime.of(2026, 10, 2, 11, 0);
        resA.put("A11", saveReservation(memberA.getId(), spaceS1.getId(), sameStart, sameStart.plusHours(1),
                ReservationStatus.CONFIRMED, null, null, null, now));
        resA.put("A12", saveReservation(memberA.getId(), spaceS1.getId(), sameStart, sameStart.plusHours(1),
                ReservationStatus.CONFIRMED, null, null, null, now));

        // A13~A17 (S1, CANCELLED)
        for (int i = 13; i <= 17; i++) {
            String label = String.format("A%02d", i);
            LocalDateTime start = LocalDateTime.of(2026, 10, 2, 13, 0).plusHours(i - 13);
            resA.put(label, saveReservation(memberA.getId(), spaceS1.getId(), start, start.plusHours(1),
                    ReservationStatus.CANCELLED, null, null, now, now));
        }

        // A18~A20 (S2, IN_USE)
        for (int i = 18; i <= 20; i++) {
            String label = String.format("A%02d", i);
            LocalDateTime start = LocalDateTime.of(2026, 10, 3, 10, 0).plusHours(i - 18);
            resA.put(label, saveReservation(memberA.getId(), spaceS2.getId(), start, start.plusHours(1),
                    ReservationStatus.IN_USE, null, now, null, now));
        }

        // A21 (S4, CANCELLED)
        LocalDateTime startA21 = LocalDateTime.of(2026, 10, 3, 14, 0);
        resA.put("A21", saveReservation(memberA.getId(), spaceS4.getId(), startA21, startA21.plusHours(1),
                ReservationStatus.CANCELLED, null, null, now, now));

        // A22~A23 (S3 INACTIVE, CONFIRMED)
        LocalDateTime startA22 = LocalDateTime.of(2026, 10, 3, 18, 0);
        resA.put("A22", saveReservation(memberA.getId(), spaceS3.getId(), startA22, startA22.plusHours(1),
                ReservationStatus.CONFIRMED, null, null, null, now));
        LocalDateTime startA23 = LocalDateTime.of(2026, 10, 3, 19, 0);
        resA.put("A23", saveReservation(memberA.getId(), spaceS3.getId(), startA23, startA23.plusHours(1),
                ReservationStatus.CONFIRMED, null, null, null, now));

        // B01~B03 (S1, CONFIRMED)
        for (int i = 1; i <= 3; i++) {
            String label = String.format("B%02d", i);
            LocalDateTime start = LocalDateTime.of(2026, 10, 1, 9, 0).plusHours(i);
            resB.put(label, saveReservation(memberB.getId(), spaceS1.getId(), start, start.plusHours(1),
                    ReservationStatus.CONFIRMED, null, null, null, now));
        }
        // B04~B05 (S4, CONFIRMED)
        for (int i = 4; i <= 5; i++) {
            String label = String.format("B%02d", i);
            LocalDateTime start = LocalDateTime.of(2026, 10, 1, 9, 0).plusHours(i);
            resB.put(label, saveReservation(memberB.getId(), spaceS4.getId(), start, start.plusHours(1),
                    ReservationStatus.CONFIRMED, null, null, null, now));
        }

        // A12.id > A11.id 확인
        assertThat(resA.get("A12").getId()).isGreaterThan(resA.get("A11").getId());
    }

    private Reservation saveReservation(Long memberId, Long spaceId, LocalDateTime start, LocalDateTime end,
                                       ReservationStatus status, LocalDateTime holdExpiresAt,
                                       LocalDateTime checkedInAt, LocalDateTime cancelledAt, LocalDateTime createdAt) {
        Reservation res = Reservation.builder()
                .memberId(memberId)
                .spaceId(spaceId)
                .startTime(start)
                .endTime(end)
                .status(status)
                .pricePerSlotSnapshot(5000)
                .totalAmount(10000)
                .holdExpiresAt(holdExpiresAt)
                .checkedInAt(checkedInAt)
                .cancelledAt(cancelledAt)
                .createdAt(createdAt)
                .build();
        return reservationRepository.save(res);
    }

    private List<Long> getIds(String... labels) {
        return Arrays.stream(labels).map(l -> resA.get(l).getId()).toList();
    }

    @Test
    @DisplayName("조건 없음(또는 null, 빈 문자열, 공백만 입력): 전체 23건 3페이지 내림차순 조회 및 합집합 전수 일치, B 예약 미혼입")
    void getMyReservations_unconditional_returns23ItemsAcross3Pages() {
        String[] blankKeywords = { null, "", "   " };
        for (String kw : blankKeywords) {
            ReservationSearchCondition condition = new ReservationSearchCondition(null, kw);

            // Page 0 (size=10): A23 ~ A14
            Page<ReservationListResponse> p0 = reservationQueryService.getMyReservations(
                    memberA.getId(), condition, PageRequest.of(0, 10));
            assertThat(p0.getTotalElements()).isEqualTo(23);
            assertThat(p0.getTotalPages()).isEqualTo(3);
            assertThat(p0.getContent()).hasSize(10);
            List<Long> p0Expected = getIds("A23", "A22", "A21", "A20", "A19", "A18", "A17", "A16", "A15", "A14");
            assertThat(p0.getContent().stream().map(ReservationListResponse::reservationId).toList())
                    .containsExactlyElementsOf(p0Expected);

            // Page 1 (size=10): A13 ~ A04
            Page<ReservationListResponse> p1 = reservationQueryService.getMyReservations(
                    memberA.getId(), condition, PageRequest.of(1, 10));
            assertThat(p1.getTotalElements()).isEqualTo(23);
            assertThat(p1.getTotalPages()).isEqualTo(3);
            assertThat(p1.getContent()).hasSize(10);
            List<Long> p1Expected = getIds("A13", "A12", "A11", "A10", "A09", "A08", "A07", "A06", "A05", "A04");
            assertThat(p1.getContent().stream().map(ReservationListResponse::reservationId).toList())
                    .containsExactlyElementsOf(p1Expected);

            // Page 2 (size=10): A03 ~ A01
            Page<ReservationListResponse> p2 = reservationQueryService.getMyReservations(
                    memberA.getId(), condition, PageRequest.of(2, 10));
            assertThat(p2.getTotalElements()).isEqualTo(23);
            assertThat(p2.getTotalPages()).isEqualTo(3);
            assertThat(p2.getContent()).hasSize(3);
            List<Long> p2Expected = getIds("A03", "A02", "A01");
            assertThat(p2.getContent().stream().map(ReservationListResponse::reservationId).toList())
                    .containsExactlyElementsOf(p2Expected);

            // 합집합 23건 중복/누락 없음 & B 예약 ID 0개 혼입
            List<Long> allCollected = new ArrayList<>();
            allCollected.addAll(p0.getContent().stream().map(ReservationListResponse::reservationId).toList());
            allCollected.addAll(p1.getContent().stream().map(ReservationListResponse::reservationId).toList());
            allCollected.addAll(p2.getContent().stream().map(ReservationListResponse::reservationId).toList());
            assertThat(allCollected).hasSize(23);
            assertThat(new HashSet<>(allCollected)).hasSize(23); // 중복 없음
            Set<Long> bIds = new HashSet<>(resB.values().stream().map(Reservation::getId).toList());
            assertThat(allCollected).doesNotContainAnyElementsOf(bIds);
        }
    }

    @Test
    @DisplayName("동일 startTime인 A11과 A12의 id DESC 정렬 단언 (A12.id > A11.id이므로 A12가 A11보다 먼저 반환됨)")
    void getMyReservations_tieBreakingByIdDesc() {
        Page<ReservationListResponse> p1 = reservationQueryService.getMyReservations(
                memberA.getId(), new ReservationSearchCondition(null, null), PageRequest.of(1, 10));

        List<Long> returnedIds = p1.getContent().stream().map(ReservationListResponse::reservationId).toList();
        int idxA12 = returnedIds.indexOf(resA.get("A12").getId());
        int idxA11 = returnedIds.indexOf(resA.get("A11").getId());

        assertThat(idxA12).isNotNegative();
        assertThat(idxA11).isNotNegative();
        assertThat(idxA12).isLessThan(idxA11); // A12가 먼저 나옴
    }

    @Test
    @DisplayName("keyword=판교(앞뒤 공백 포함): 위치/설명에만 판교가 있는 S4 제외, INACTIVE S3 포함하여 19건 반환")
    void getMyReservations_keywordPangyo_returns19Items() {
        String[] keywords = { "판교", "  판교  " };
        for (String kw : keywords) {
            ReservationSearchCondition condition = new ReservationSearchCondition(null, kw);

            Page<ReservationListResponse> p0 = reservationQueryService.getMyReservations(
                    memberA.getId(), condition, PageRequest.of(0, 10));
            assertThat(p0.getTotalElements()).isEqualTo(19);
            assertThat(p0.getTotalPages()).isEqualTo(2);
            assertThat(p0.getContent()).hasSize(10);
            List<Long> p0Expected = getIds("A23", "A22", "A17", "A16", "A15", "A14", "A13", "A12", "A11", "A10");
            assertThat(p0.getContent().stream().map(ReservationListResponse::reservationId).toList())
                    .containsExactlyElementsOf(p0Expected);

            Page<ReservationListResponse> p1 = reservationQueryService.getMyReservations(
                    memberA.getId(), condition, PageRequest.of(1, 10));
            assertThat(p1.getTotalElements()).isEqualTo(19);
            assertThat(p1.getTotalPages()).isEqualTo(2);
            assertThat(p1.getContent()).hasSize(9);
            List<Long> p1Expected = getIds("A09", "A08", "A07", "A06", "A05", "A04", "A03", "A02", "A01");
            assertThat(p1.getContent().stream().map(ReservationListResponse::reservationId).toList())
                    .containsExactlyElementsOf(p1Expected);

            // S4(A21)는 위치/설명에만 판교가 있으므로 제외됨
            assertThat(p0.getContent().stream().map(ReservationListResponse::reservationId).toList())
                    .doesNotContain(resA.get("A21").getId());
            assertThat(p1.getContent().stream().map(ReservationListResponse::reservationId).toList())
                    .doesNotContain(resA.get("A21").getId());
        }
    }

    @Test
    @DisplayName("status=CONFIRMED: 총 14건 2페이지 반환 (A23, A22, A12~A01)")
    void getMyReservations_statusConfirmed_returns14Items() {
        ReservationSearchCondition condition = new ReservationSearchCondition(ReservationStatus.CONFIRMED, null);

        Page<ReservationListResponse> p0 = reservationQueryService.getMyReservations(
                memberA.getId(), condition, PageRequest.of(0, 10));
        assertThat(p0.getTotalElements()).isEqualTo(14);
        assertThat(p0.getTotalPages()).isEqualTo(2);
        assertThat(p0.getContent()).hasSize(10);
        List<Long> p0Expected = getIds("A23", "A22", "A12", "A11", "A10", "A09", "A08", "A07", "A06", "A05");
        assertThat(p0.getContent().stream().map(ReservationListResponse::reservationId).toList())
                .containsExactlyElementsOf(p0Expected);

        Page<ReservationListResponse> p1 = reservationQueryService.getMyReservations(
                memberA.getId(), condition, PageRequest.of(1, 10));
        assertThat(p1.getContent()).hasSize(4);
        List<Long> p1Expected = getIds("A04", "A03", "A02", "A01");
        assertThat(p1.getContent().stream().map(ReservationListResponse::reservationId).toList())
                .containsExactlyElementsOf(p1Expected);
    }

    @Test
    @DisplayName("keyword=판교 + status=CONFIRMED: 총 14건 2페이지 반환 (A23, A22, A12~A01)")
    void getMyReservations_keywordPangyoAndStatusConfirmed_returns14Items() {
        ReservationSearchCondition condition = new ReservationSearchCondition(ReservationStatus.CONFIRMED, "판교");

        Page<ReservationListResponse> p0 = reservationQueryService.getMyReservations(
                memberA.getId(), condition, PageRequest.of(0, 10));
        assertThat(p0.getTotalElements()).isEqualTo(14);
        assertThat(p0.getTotalPages()).isEqualTo(2);
        assertThat(p0.getContent()).hasSize(10);
        List<Long> p0Expected = getIds("A23", "A22", "A12", "A11", "A10", "A09", "A08", "A07", "A06", "A05");
        assertThat(p0.getContent().stream().map(ReservationListResponse::reservationId).toList())
                .containsExactlyElementsOf(p0Expected);

        Page<ReservationListResponse> p1 = reservationQueryService.getMyReservations(
                memberA.getId(), condition, PageRequest.of(1, 10));
        assertThat(p1.getTotalElements()).isEqualTo(14);
        assertThat(p1.getTotalPages()).isEqualTo(2);
        assertThat(p1.getContent()).hasSize(4);
        List<Long> p1Expected = getIds("A04", "A03", "A02", "A01");
        assertThat(p1.getContent().stream().map(ReservationListResponse::reservationId).toList())
                .containsExactlyElementsOf(p1Expected);
    }

    @Test
    @DisplayName("keyword=판교 + status=CANCELLED: 총 5건 1페이지 반환 (A17~A13, S4의 A21은 제외)")
    void getMyReservations_keywordPangyoAndStatusCancelled_returns5Items() {
        ReservationSearchCondition condition = new ReservationSearchCondition(ReservationStatus.CANCELLED, "판교");

        Page<ReservationListResponse> p0 = reservationQueryService.getMyReservations(
                memberA.getId(), condition, PageRequest.of(0, 10));
        assertThat(p0.getTotalElements()).isEqualTo(5);
        assertThat(p0.getTotalPages()).isEqualTo(1);
        assertThat(p0.getContent()).hasSize(5);
        List<Long> expected = getIds("A17", "A16", "A15", "A14", "A13");
        assertThat(p0.getContent().stream().map(ReservationListResponse::reservationId).toList())
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("keyword=라운지 + status=IN_USE: 총 3건 1페이지 반환 (A20~A18)")
    void getMyReservations_keywordLoungeAndStatusInUse_returns3Items() {
        ReservationSearchCondition condition = new ReservationSearchCondition(ReservationStatus.IN_USE, "라운지");

        Page<ReservationListResponse> p0 = reservationQueryService.getMyReservations(
                memberA.getId(), condition, PageRequest.of(0, 10));
        assertThat(p0.getTotalElements()).isEqualTo(3);
        assertThat(p0.getTotalPages()).isEqualTo(1);
        assertThat(p0.getContent()).hasSize(3);
        List<Long> expected = getIds("A20", "A19", "A18");
        assertThat(p0.getContent().stream().map(ReservationListResponse::reservationId).toList())
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("keyword=라운지 + status=CONFIRMED 또는 keyword=미등록: 0건 빈 목록 반환")
    void getMyReservations_noMatch_returnsEmptyPage() {
        ReservationSearchCondition c1 = new ReservationSearchCondition(ReservationStatus.CONFIRMED, "라운지");
        Page<ReservationListResponse> p1 = reservationQueryService.getMyReservations(
                memberA.getId(), c1, PageRequest.of(0, 10));
        assertThat(p1.getTotalElements()).isEqualTo(0);
        assertThat(p1.getTotalPages()).isEqualTo(0);
        assertThat(p1.getContent()).isEmpty();

        ReservationSearchCondition c2 = new ReservationSearchCondition(null, "미등록");
        Page<ReservationListResponse> p2 = reservationQueryService.getMyReservations(
                memberA.getId(), c2, PageRequest.of(0, 10));
        assertThat(p2.getTotalElements()).isEqualTo(0);
        assertThat(p2.getTotalPages()).isEqualTo(0);
        assertThat(p2.getContent()).isEmpty();
    }

    @Test
    @DisplayName("범위 밖 page=3 요청 시 content=[]이지만 totalElements=23, totalPages=3은 보존됨")
    void getMyReservations_outOfBoundsPage_preservesTotals() {
        Page<ReservationListResponse> p3 = reservationQueryService.getMyReservations(
                memberA.getId(), new ReservationSearchCondition(null, null), PageRequest.of(3, 10));
        assertThat(p3.getTotalElements()).isEqualTo(23);
        assertThat(p3.getTotalPages()).isEqualTo(3);
        assertThat(p3.getContent()).isEmpty();
    }

    @Test
    @DisplayName("클라이언트 요청 sort=ASC 무시하고 서버 고정 정렬(startTime DESC, id DESC) 유지 및 size=101은 100으로 제한")
    void getMyReservations_clientSortIgnoredAndSizeCapped() {
        // 클라이언트가 sort=startTime: ASC를 요청해도 무시되고 DESC로 조회됨
        Page<ReservationListResponse> p = reservationQueryService.getMyReservations(
                memberA.getId(),
                new ReservationSearchCondition(null, null),
                PageRequest.of(0, 101, Sort.by(Sort.Direction.ASC, "startTime"))
        );
        assertThat(p.getSize()).isEqualTo(100);
        assertThat(p.getContent()).hasSize(23); // 전체 23개 모두 들어옴
        // 첫 번째 원소는 A23 (가장 늦은 시간), 마지막은 A01 (가장 이른 시간)
        assertThat(p.getContent().get(0).reservationId()).isEqualTo(resA.get("A23").getId());
        assertThat(p.getContent().get(22).reservationId()).isEqualTo(resA.get("A01").getId());
    }

    @Test
    @DisplayName("공간 이름 변경 시: 새 이름으로 즉시 검색되고 이전 이름으로는 검색되지 않으며 DTO의 spaceName도 갱신됨")
    void getMyReservations_spaceNameUpdateReflectedInSearchAndResponse() {
        // S1 이름을 '판교 회의실' -> '분당 회의실'로 변경
        spaceS1.updateDetail(new com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest(
                null, "분당 회의실", null, null, null, null, null, null, null, null
        ));
        spaceRepository.save(spaceS1);

        // keyword=분당 검색 -> S1의 17건(A17~A01) 반환
        Page<ReservationListResponse> pBundang = reservationQueryService.getMyReservations(
                memberA.getId(), new ReservationSearchCondition(null, "분당"), PageRequest.of(0, 20));
        assertThat(pBundang.getTotalElements()).isEqualTo(17);
        assertThat(pBundang.getContent().get(0).spaceName()).isEqualTo("분당 회의실");

        // keyword=판교 검색 -> S3의 2건(A23, A22)만 반환
        Page<ReservationListResponse> pPangyo = reservationQueryService.getMyReservations(
                memberA.getId(), new ReservationSearchCondition(null, "판교"), PageRequest.of(0, 20));
        assertThat(pPangyo.getTotalElements()).isEqualTo(2);

        // keyword=판교 회의실 (이전 이름) 검색 -> 0건
        Page<ReservationListResponse> pOld = reservationQueryService.getMyReservations(
                memberA.getId(), new ReservationSearchCondition(null, "판교 회의실"), PageRequest.of(0, 20));
        assertThat(pOld.getTotalElements()).isEqualTo(0);
    }

    @Test
    @DisplayName("DTO 필드 검증: date, startTime, endTime, spaceName, spaceLocation, spaceImagePath, totalAmount 등 계약 일치")
    void getMyReservations_dtoFieldsMatchContract() {
        Page<ReservationListResponse> page = reservationQueryService.getMyReservations(
                memberA.getId(),
                new ReservationSearchCondition(ReservationStatus.IN_USE, "라운지"),
                PageRequest.of(0, 10)
        );

        assertThat(page.getContent()).isNotEmpty();
        ReservationListResponse dto = page.getContent().get(0);
        Reservation expectedEntity = resA.get("A20");

        assertThat(dto.reservationId()).isEqualTo(expectedEntity.getId());
        assertThat(dto.spaceId()).isEqualTo(spaceS2.getId());
        assertThat(dto.spaceName()).isEqualTo("강남 라운지");
        assertThat(dto.spaceLocation()).isEqualTo("서울시 강남구");
        assertThat(dto.spaceImagePath()).isEqualTo("/images/gangnam-lounge.jpg");
        assertThat(dto.status()).isEqualTo("IN_USE");
        assertThat(dto.date()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(dto.startTime()).isEqualTo(expectedEntity.getStartTime());
        assertThat(dto.endTime()).isEqualTo(expectedEntity.getEndTime());
        assertThat(dto.totalAmount()).isEqualTo(10000);
        assertThat(dto.pricePerSlotSnapshot()).isEqualTo(5000);
    }

    @AfterEach
    void tearDown() {
        reservationStatusHistoryRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        spaceRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }
}
