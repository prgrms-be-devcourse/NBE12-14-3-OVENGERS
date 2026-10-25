package com.ovengers.slotkey.space.repository;

import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import com.ovengers.slotkey.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class SpaceRepositoryTest extends IntegrationTestSupport {

    private static final List<Long> NO_EXCLUSION = List.of(-1L);

    @Autowired
    private SpaceRepository spaceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Space spaceAlpha;
    private Space spaceBeta;
    private Space spaceGamma;
    private Space spaceDelta;

    @BeforeEach
    void setUp() {
        spaceRepository.deleteAll();

        // 101: 판교 회의실 알파 (이름에만 '판교', 기본 영업시간 09:00~18:00, 5000원, ACTIVE)
        spaceAlpha = spaceRepository.save(Space.builder()
                .name("판교 회의실 알파")
                .location("성남시 분당구 중앙로")
                .description("집중하기 좋은 쾌적한 회의실")
                .capacity(6)
                .pricePerSlot(5000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build());

        // 102: 강남 세미나실 베타 (설명에만 '판교', 10:00~22:00, 10000원, ACTIVE)
        spaceBeta = spaceRepository.save(Space.builder()
                .name("강남 세미나실 베타")
                .location("서울시 강남구 테헤란로")
                .description("판교 테크팀 추천 대형 세미나실")
                .capacity(20)
                .pricePerSlot(10000L)
                .openingTime(LocalTime.of(10, 0))
                .closingTime(LocalTime.of(22, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build());

        // 103: 스튜디오 감마 (위치에만 '하남', 09:00~18:00, 100원, ACTIVE)
        spaceGamma = spaceRepository.save(Space.builder()
                .name("스튜디오 감마")
                .location("경기도 하남시 미사대로")
                .description("영상 촬영 및 라이브 방송 전용")
                .capacity(4)
                .pricePerSlot(100L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build());

        // 104: 라운지 델타 (위치에만 '판교', 09:00~18:00, 3000원, INACTIVE)
        spaceDelta = spaceRepository.save(Space.builder()
                .name("라운지 델타")
                .location("성남시 분당구 판교역로")
                .description("네트워킹 전용 오픈 라운지")
                .capacity(10)
                .pricePerSlot(3000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.INACTIVE)
                .version(0)
                .build());
    }

    @Test
    @DisplayName("keyword=판교 검색 시 공개 목록은 이름/설명 매칭 2건(ACTIVE), 관리자 목록은 위치 매칭 포함 3건(INACTIVE 포함)을 반환한다")
    void searchSpaces_keywordPangyo() {
        Pageable pageable = PageRequest.of(0, 10);

        // 공개 검색 (ACTIVE만): spaceAlpha(이름), spaceBeta(설명)
        Page<Space> publicResult = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, "판교", null, null, null, null, null, NO_EXCLUSION, pageable);
        assertThat(publicResult.getContent())
                .extracting(Space::getId)
                .containsExactlyInAnyOrder(spaceAlpha.getId(), spaceBeta.getId());
        assertThat(publicResult.getTotalElements()).isEqualTo(2);

        // 관리자 검색 (전체 상태): spaceAlpha(이름), spaceBeta(설명), spaceDelta(위치·INACTIVE)
        Page<Space> adminResult = spaceRepository.searchSpacesByNameOrDescription(
                "판교", null, pageable);
        assertThat(adminResult.getContent())
                .extracting(Space::getId)
                .containsExactlyInAnyOrder(spaceAlpha.getId(), spaceBeta.getId(), spaceDelta.getId());
        assertThat(adminResult.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("keyword=하남 검색 시 위치에만 매칭되는 스튜디오 감마 1건이 검색된다 (위치 OR 미적용 시 실패 보장)")
    void searchSpaces_keywordHanam_locationMatch() {
        Pageable pageable = PageRequest.of(0, 10);

        // 공개 검색
        Page<Space> publicResult = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, "하남", null, null, null, null, null, NO_EXCLUSION, pageable);
        assertThat(publicResult.getContent())
                .extracting(Space::getId)
                .containsExactly(spaceGamma.getId());
        assertThat(publicResult.getTotalElements()).isEqualTo(1);

        // 관리자 검색
        Page<Space> adminResult = spaceRepository.searchSpacesByNameOrDescription(
                "하남", null, pageable);
        assertThat(adminResult.getContent())
                .extracting(Space::getId)
                .containsExactly(spaceGamma.getId());
        assertThat(adminResult.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("keyword=테크팀 검색 시 설명에만 매칭되는 세미나실 베타 1건이 검색된다")
    void searchSpaces_keywordDescriptionMatch() {
        Pageable pageable = PageRequest.of(0, 10);

        Page<Space> result = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, "테크팀", null, null, null, null, null, NO_EXCLUSION, pageable);
        assertThat(result.getContent())
                .extracting(Space::getId)
                .containsExactly(spaceBeta.getId());
    }

    @Test
    @DisplayName("keyword=null일 때 공개 목록은 ACTIVE 3건, 관리자 목록은 전체 4건을 반환한다")
    void searchSpaces_nullKeyword() {
        Pageable pageable = PageRequest.of(0, 10);

        Page<Space> publicResult = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, null, null, null, NO_EXCLUSION, pageable);
        assertThat(publicResult.getContent())
                .extracting(Space::getId)
                .containsExactlyInAnyOrder(spaceAlpha.getId(), spaceBeta.getId(), spaceGamma.getId());
        assertThat(publicResult.getTotalElements()).isEqualTo(3);

        Page<Space> adminResult = spaceRepository.searchSpacesByNameOrDescription(
                null, null, pageable);
        assertThat(adminResult.getContent())
                .extracting(Space::getId)
                .containsExactlyInAnyOrder(spaceAlpha.getId(), spaceBeta.getId(), spaceGamma.getId(), spaceDelta.getId());
        assertThat(adminResult.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("가격 필터 minPrice=6000 검색 시 10000원인 세미나실 베타 1건만 반환한다")
    void searchSpaces_minPriceFilter() {
        Pageable pageable = PageRequest.of(0, 10);

        Page<Space> result = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, 6000L, null, null, null, NO_EXCLUSION, pageable);
        assertThat(result.getContent())
                .extracting(Space::getId)
                .containsExactly(spaceBeta.getId());
    }

    @Test
    @DisplayName("가격 필터 maxPrice=4000 검색 시 ACTIVE 공간 중 100원인 스튜디오 감마 1건만 반환한다")
    void searchSpaces_maxPriceFilter() {
        Pageable pageable = PageRequest.of(0, 10);

        Page<Space> result = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, 4000L, null, null, NO_EXCLUSION, pageable);
        assertThat(result.getContent())
                .extracting(Space::getId)
                .containsExactly(spaceGamma.getId());
    }

    @Test
    @DisplayName("영업시간 필터 09:00~18:00 검색 시 10시 오픈인 세미나실 베타를 제외한 2건을 반환한다")
    void searchSpaces_operatingHours_09to18() {
        Pageable pageable = PageRequest.of(0, 10);

        Page<Space> result = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, null,
                LocalTime.of(9, 0), LocalTime.of(18, 0), NO_EXCLUSION, pageable);
        assertThat(result.getContent())
                .extracting(Space::getId)
                .containsExactlyInAnyOrder(spaceAlpha.getId(), spaceGamma.getId());
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("영업시간 필터 10:00~12:00 검색 시 모든 ACTIVE 공간 3건이 정상 반환된다")
    void searchSpaces_operatingHours_10to12() {
        Pageable pageable = PageRequest.of(0, 10);

        Page<Space> result = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, null,
                LocalTime.of(10, 0), LocalTime.of(12, 0), NO_EXCLUSION, pageable);
        assertThat(result.getContent())
                .extracting(Space::getId)
                .containsExactlyInAnyOrder(spaceAlpha.getId(), spaceBeta.getId(), spaceGamma.getId());
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("영업시간 필터 18:00~20:00 검색 시 18시 마감인 알파/감마를 제외하고 야간 영업하는 세미나실 베타 1건만 반환한다")
    void searchSpaces_operatingHours_18to20() {
        Pageable pageable = PageRequest.of(0, 10);

        Page<Space> result = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, null,
                LocalTime.of(18, 0), LocalTime.of(20, 0), NO_EXCLUSION, pageable);
        assertThat(result.getContent())
                .extracting(Space::getId)
                .containsExactly(spaceBeta.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("점유 슬롯 제외 ID 목록이 주어지면 해당 공간이 결과에서 제외된다")
    void searchSpaces_excludedSpaceIds() {
        Pageable pageable = PageRequest.of(0, 10);
        List<Long> excluded = List.of(spaceAlpha.getId());

        Page<Space> result = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, null,
                null, null, excluded, pageable);
        assertThat(result.getContent())
                .extracting(Space::getId)
                .containsExactlyInAnyOrder(spaceBeta.getId(), spaceGamma.getId());
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("페이징 요청 시 size와 page에 맞게 슬라이스되고 전체 count 및 totalPages가 정확하게 계산된다")
    void searchSpaces_paginationAndCountQuery() {
        // 1페이지 (size=2)
        Pageable page0 = PageRequest.of(0, 2);
        Page<Space> resultPage0 = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, null,
                null, null, NO_EXCLUSION, page0);
        assertThat(resultPage0.getContent()).hasSize(2);
        assertThat(resultPage0.getTotalElements()).isEqualTo(3);
        assertThat(resultPage0.getTotalPages()).isEqualTo(2);
        assertThat(resultPage0.getNumber()).isEqualTo(0);

        // 2페이지 (size=2)
        Pageable page1 = PageRequest.of(1, 2);
        Page<Space> resultPage1 = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, null,
                null, null, NO_EXCLUSION, page1);
        assertThat(resultPage1.getContent()).hasSize(1);
        assertThat(resultPage1.getTotalElements()).isEqualTo(3);
        assertThat(resultPage1.getTotalPages()).isEqualTo(2);
        assertThat(resultPage1.getNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("관리자 검색: keyword=판교 + status=ACTIVE 결합 시 INACTIVE인 라운지 델타를 제외하고 ACTIVE 2건만 반환한다")
    void searchSpacesByNameOrDescription_keywordPangyo_withStatusActive() {
        Pageable pageable = PageRequest.of(0, 10);

        Page<Space> result = spaceRepository.searchSpacesByNameOrDescription(
                "판교", SpaceStatus.ACTIVE, pageable);

        assertThat(result.getContent())
                .extracting(Space::getId)
                .containsExactlyInAnyOrder(spaceAlpha.getId(), spaceBeta.getId());
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("관리자 검색: keyword=판교 + status=INACTIVE 결합 시 ACTIVE인 알파/베타를 제외하고 위치 매칭된 비활성 델타 1건만 반환한다")
    void searchSpacesByNameOrDescription_keywordPangyo_withStatusInactive() {
        Pageable pageable = PageRequest.of(0, 10);

        Page<Space> result = spaceRepository.searchSpacesByNameOrDescription(
                "판교", SpaceStatus.INACTIVE, pageable);

        assertThat(result.getContent())
                .extracting(Space::getId)
                .containsExactly(spaceDelta.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("공개 검색: keyword + location + 가격 + 영업시간 + 점유 제외 ID 복합 조건 동시 적용 시 정확히 1건만 반환한다")
    void searchSpaces_combinedFullFilters() {
        Pageable pageable = PageRequest.of(0, 10);
        List<Long> excluded = List.of(spaceBeta.getId());

        Page<Space> result = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                "판교",                   // keyword: 알파(이름), 베타(설명) 매칭
                "중앙로",                 // location: 알파 매칭 (베타 테헤란로 제외)
                4000L,                   // minPrice
                6000L,                   // maxPrice (알파 5000원 매칭)
                LocalTime.of(9, 0),      // startTime
                LocalTime.of(18, 0),     // endTime
                excluded,                // 점유 제외 목록에 베타 포함
                pageable
        );

        assertThat(result.getContent())
                .extracting(Space::getId)
                .containsExactly(spaceAlpha.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("영업시간 안의 점유 공간과 영업시간 밖의 비점유 공간이 동시에 제외되어 감마 1건만 반환된다")
    void searchSpaces_combinedOperatingHoursAndExcludedOccupiedSlots() {
        Pageable pageable = PageRequest.of(0, 10);
        // 영업시간 09:00~18:00 -> 10시 오픈인 베타(비점유)는 영업시간 불일치로 제외
        // 알파는 영업시간 안이지만 점유 슬롯 제외 ID로 지정되어 제외
        List<Long> excludedOccupied = List.of(spaceAlpha.getId());

        Page<Space> result = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null, null, null, null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                excludedOccupied,
                pageable
        );

        assertThat(result.getContent())
                .extracting(Space::getId)
                .containsExactly(spaceGamma.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("공개 검색: 작은 page size(2)와 정렬로 전 페이지를 조회할 때 중복 및 누락 없이 전체 집합이 일치한다")
    void searchSpaces_paginationWithSortingAndAllPagesIntegrity() {
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.by("id").ascending();
        Pageable page0 = PageRequest.of(0, 2, sort);
        Pageable page1 = PageRequest.of(1, 2, sort);

        Page<Space> resultPage0 = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, null,
                null, null, NO_EXCLUSION, page0);
        Page<Space> resultPage1 = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, null,
                null, null, NO_EXCLUSION, page1);

        assertThat(resultPage0.getContent()).hasSize(2);
        assertThat(resultPage1.getContent()).hasSize(1);
        assertThat(resultPage0.getTotalElements()).isEqualTo(3);
        assertThat(resultPage0.getTotalPages()).isEqualTo(2);

        List<Long> page0Ids = resultPage0.getContent().stream().map(Space::getId).toList();
        List<Long> page1Ids = resultPage1.getContent().stream().map(Space::getId).toList();

        // 페이지 간 중복 ID 없음
        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);

        // 전 페이지 합집합이 전체 ACTIVE 3건(알파, 베타, 감마)과 일치
        java.util.List<Long> combinedIds = new java.util.ArrayList<>(page0Ids);
        combinedIds.addAll(page1Ids);
        assertThat(combinedIds)
                .containsExactlyInAnyOrder(spaceAlpha.getId(), spaceBeta.getId(), spaceGamma.getId());
    }

    @Test
    @DisplayName("관리자 검색: keyword=판교 조건에서 page size(2) 조회 시 필터 count(3) 및 전 페이지 ID가 일치한다")
    void searchSpacesByNameOrDescription_paginationAndFilterCount() {
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.by("id").ascending();
        Pageable page0 = PageRequest.of(0, 2, sort);
        Pageable page1 = PageRequest.of(1, 2, sort);

        Page<Space> resultPage0 = spaceRepository.searchSpacesByNameOrDescription(
                "판교", null, page0);
        Page<Space> resultPage1 = spaceRepository.searchSpacesByNameOrDescription(
                "판교", null, page1);

        assertThat(resultPage0.getTotalElements()).isEqualTo(3);
        assertThat(resultPage0.getTotalPages()).isEqualTo(2);
        assertThat(resultPage0.getContent()).hasSize(2);
        assertThat(resultPage1.getContent()).hasSize(1);

        List<Long> page0Ids = resultPage0.getContent().stream().map(Space::getId).toList();
        List<Long> page1Ids = resultPage1.getContent().stream().map(Space::getId).toList();

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);

        java.util.List<Long> combinedIds = new java.util.ArrayList<>(page0Ids);
        combinedIds.addAll(page1Ids);
        // 전체 3건 (알파[이름], 베타[설명], 델타[위치·INACTIVE])
        assertThat(combinedIds)
                .containsExactlyInAnyOrder(spaceAlpha.getId(), spaceBeta.getId(), spaceDelta.getId());
    }

    @Test
    @DisplayName("관리자 검색: 선택 상태(ACTIVE) + keyword=판교 조건에서 page size(1) 조회 시 count(2) 및 전 페이지 ID가 일치한다")
    void searchSpacesByNameOrDescription_adminActiveKeywordPagination() {
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.by("id").ascending();
        Pageable page0 = PageRequest.of(0, 1, sort);
        Pageable page1 = PageRequest.of(1, 1, sort);

        Page<Space> resultPage0 = spaceRepository.searchSpacesByNameOrDescription(
                "판교", SpaceStatus.ACTIVE, page0);
        Page<Space> resultPage1 = spaceRepository.searchSpacesByNameOrDescription(
                "판교", SpaceStatus.ACTIVE, page1);

        assertThat(resultPage0.getTotalElements()).isEqualTo(2);
        assertThat(resultPage0.getTotalPages()).isEqualTo(2);
        assertThat(resultPage0.getContent()).hasSize(1);
        assertThat(resultPage1.getContent()).hasSize(1);

        List<Long> page0Ids = resultPage0.getContent().stream().map(Space::getId).toList();
        List<Long> page1Ids = resultPage1.getContent().stream().map(Space::getId).toList();

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);

        java.util.List<Long> combinedIds = new java.util.ArrayList<>(page0Ids);
        combinedIds.addAll(page1Ids);
        // INACTIVE 델타 제외된 알파(이름), 베타(설명) 2건
        assertThat(combinedIds)
                .containsExactlyInAnyOrder(spaceAlpha.getId(), spaceBeta.getId());
    }

    @Test
    @DisplayName("공개 검색: maxPrice=6000 필터 조건에서 page size(1) 조회 시 count(2) 및 전 페이지 ID가 일치한다")
    void searchSpaces_publicFilterPaginationAndCount() {
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.by("id").ascending();
        Pageable page0 = PageRequest.of(0, 1, sort);
        Pageable page1 = PageRequest.of(1, 1, sort);

        Page<Space> resultPage0 = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, 6000L,
                null, null, NO_EXCLUSION, page0);
        Page<Space> resultPage1 = spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE, null, null, null, 6000L,
                null, null, NO_EXCLUSION, page1);

        assertThat(resultPage0.getTotalElements()).isEqualTo(2);
        assertThat(resultPage0.getTotalPages()).isEqualTo(2);
        assertThat(resultPage0.getContent()).hasSize(1);
        assertThat(resultPage1.getContent()).hasSize(1);

        List<Long> page0Ids = resultPage0.getContent().stream().map(Space::getId).toList();
        List<Long> page1Ids = resultPage1.getContent().stream().map(Space::getId).toList();

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);

        java.util.List<Long> combinedIds = new java.util.ArrayList<>(page0Ids);
        combinedIds.addAll(page1Ids);
        // 알파(5000원)와 감마(100원) 2건 매칭, 베타(10000원) 제외, 델타(INACTIVE) 제외
        assertThat(combinedIds)
                .containsExactlyInAnyOrder(spaceAlpha.getId(), spaceGamma.getId());
    }

    @Test
    @DisplayName("Space 저장 후 flush 및 clear 후 재조회 시 모든 필드가 정상 유지된다")
    void saveAndFlushAndClear_roundTrip() {
        Space newSpace = Space.builder()
                .name("라운드트립 공간")
                .location("서울시 강남구")
                .description("라운드트립 테스트 설명")
                .capacity(12)
                .pricePerSlot(15000L)
                .imagePath("/images/roundtrip.jpg")
                .openingTime(LocalTime.of(8, 0))
                .closingTime(LocalTime.of(20, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build();

        Space saved = spaceRepository.save(newSpace);
        entityManager.flush();
        entityManager.clear();

        Space found = spaceRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getName()).isEqualTo("라운드트립 공간");
        assertThat(found.getLocation()).isEqualTo("서울시 강남구");
        assertThat(found.getDescription()).isEqualTo("라운드트립 테스트 설명");
        assertThat(found.getCapacity()).isEqualTo(12);
        assertThat(found.getPricePerSlot()).isEqualTo(15000L);
        assertThat(found.getImagePath()).isEqualTo("/images/roundtrip.jpg");
        assertThat(found.getOpeningTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(found.getClosingTime()).isEqualTo(LocalTime.of(20, 0));
        assertThat(found.getStatus()).isEqualTo(SpaceStatus.ACTIVE);
        assertThat(found.getVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("em.getReference 프록시는 비ID 프로퍼티 접근 전까지 미초기화 상태이고 접근 시 정상 초기화된다")
    void getReference_proxyUninitializedUntilNonIdPropertyAccess() {
        entityManager.flush();
        entityManager.clear();

        Long id = spaceAlpha.getId();
        Space proxy = entityManager.getReference(Space.class, id);

        // 프록시 생성 직후 미초기화 상태 검증 (id 조회는 프록시를 초기화하지 않음)
        assertThat(Hibernate.isInitialized(proxy)).isFalse();
        assertThat(proxy.getId()).isEqualTo(id);
        assertThat(Hibernate.isInitialized(proxy)).isFalse();

        // 첫 비ID 프로퍼티 접근 시 초기화 검증
        assertThat(proxy.getName()).isEqualTo("판교 회의실 알파");
        assertThat(Hibernate.isInitialized(proxy)).isTrue();
    }
}

