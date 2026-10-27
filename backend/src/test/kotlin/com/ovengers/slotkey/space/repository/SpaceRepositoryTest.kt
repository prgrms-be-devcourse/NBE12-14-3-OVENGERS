package com.ovengers.slotkey.space.repository

import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.support.IntegrationTestSupport
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.Hibernate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.transaction.annotation.Transactional
import java.time.LocalTime

@Transactional
class SpaceRepositoryTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    private lateinit var spaceAlpha: Space
    private lateinit var spaceBeta: Space
    private lateinit var spaceGamma: Space
    private lateinit var spaceDelta: Space

    @BeforeEach
    fun setUp() {
        spaceRepository.deleteAll()

        // 101: 판교 회의실 알파 (이름에만 '판교', 기본 영업시간 09:00~18:00, 5000원, ACTIVE)
        spaceAlpha =
            spaceRepository.save(
                Space
                    .builder()
                    .name("판교 회의실 알파")
                    .location("성남시 분당구 중앙로")
                    .description("집중하기 좋은 쾌적한 회의실")
                    .capacity(6)
                    .pricePerSlot(5000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(18, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )

        // 102: 강남 세미나실 베타 (설명에만 '판교', 10:00~22:00, 10000원, ACTIVE)
        spaceBeta =
            spaceRepository.save(
                Space
                    .builder()
                    .name("강남 세미나실 베타")
                    .location("서울시 강남구 테헤란로")
                    .description("판교 테크팀 추천 대형 세미나실")
                    .capacity(20)
                    .pricePerSlot(10000L)
                    .openingTime(LocalTime.of(10, 0))
                    .closingTime(LocalTime.of(22, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )

        // 103: 스튜디오 감마 (위치에만 '하남', 09:00~18:00, 100원, ACTIVE)
        spaceGamma =
            spaceRepository.save(
                Space
                    .builder()
                    .name("스튜디오 감마")
                    .location("경기도 하남시 미사대로")
                    .description("영상 촬영 및 라이브 방송 전용")
                    .capacity(4)
                    .pricePerSlot(100L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(18, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )

        // 104: 라운지 델타 (위치에만 '판교', 09:00~18:00, 3000원, INACTIVE)
        spaceDelta =
            spaceRepository.save(
                Space
                    .builder()
                    .name("라운지 델타")
                    .location("성남시 분당구 판교역로")
                    .description("네트워킹 전용 오픈 라운지")
                    .capacity(10)
                    .pricePerSlot(3000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(18, 0))
                    .status(SpaceStatus.INACTIVE)
                    .version(0)
                    .build(),
            )
    }

    @Test
    @DisplayName("keyword=판교 검색 시 공개 목록은 이름/설명 매칭 2건(ACTIVE), 관리자 목록은 위치 매칭 포함 3건(INACTIVE 포함)을 반환한다")
    fun searchSpaces_keywordPangyo() {
        val pageable: Pageable = PageRequest.of(0, 10)

        // 공개 검색 (ACTIVE만): spaceAlpha(이름), spaceBeta(설명)
        val publicResult: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                "판교",
                null,
                null,
                null,
                null,
                null,
                NO_EXCLUSION,
                pageable,
            )
        assertThat(publicResult.content.map { it.id })
            .containsExactlyInAnyOrder(spaceAlpha.id, spaceBeta.id)
        assertThat(publicResult.totalElements).isEqualTo(2L)

        // 관리자 검색 (전체 상태): spaceAlpha(이름), spaceBeta(설명), spaceDelta(위치·INACTIVE)
        val adminResult: Page<Space> =
            spaceRepository.searchSpacesByNameOrDescription(
                "판교",
                null,
                pageable,
            )
        assertThat(adminResult.content.map { it.id })
            .containsExactlyInAnyOrder(spaceAlpha.id, spaceBeta.id, spaceDelta.id)
        assertThat(adminResult.totalElements).isEqualTo(3L)
    }

    @Test
    @DisplayName("keyword=하남 검색 시 위치에만 매칭되는 스튜디오 감마 1건이 검색된다 (위치 OR 미적용 시 실패 보장)")
    fun searchSpaces_keywordHanam_locationMatch() {
        val pageable: Pageable = PageRequest.of(0, 10)

        // 공개 검색
        val publicResult: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                "하남",
                null,
                null,
                null,
                null,
                null,
                NO_EXCLUSION,
                pageable,
            )
        assertThat(publicResult.content.map { it.id })
            .containsExactly(spaceGamma.id)
        assertThat(publicResult.totalElements).isEqualTo(1L)

        // 관리자 검색
        val adminResult: Page<Space> =
            spaceRepository.searchSpacesByNameOrDescription(
                "하남",
                null,
                pageable,
            )
        assertThat(adminResult.content.map { it.id })
            .containsExactly(spaceGamma.id)
        assertThat(adminResult.totalElements).isEqualTo(1L)
    }

    @Test
    @DisplayName("keyword=테크팀 검색 시 설명에만 매칭되는 세미나실 베타 1건이 검색된다")
    fun searchSpaces_keywordDescriptionMatch() {
        val pageable: Pageable = PageRequest.of(0, 10)

        val result: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                "테크팀",
                null,
                null,
                null,
                null,
                null,
                NO_EXCLUSION,
                pageable,
            )
        assertThat(result.content.map { it.id })
            .containsExactly(spaceBeta.id)
    }

    @Test
    @DisplayName("keyword=null일 때 공개 목록은 ACTIVE 3건, 관리자 목록은 전체 4건을 반환한다")
    fun searchSpaces_nullKeyword() {
        val pageable: Pageable = PageRequest.of(0, 10)

        val publicResult: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                null,
                null,
                NO_EXCLUSION,
                pageable,
            )
        assertThat(publicResult.content.map { it.id })
            .containsExactlyInAnyOrder(spaceAlpha.id, spaceBeta.id, spaceGamma.id)
        assertThat(publicResult.totalElements).isEqualTo(3L)

        val adminResult: Page<Space> =
            spaceRepository.searchSpacesByNameOrDescription(
                null,
                null,
                pageable,
            )
        assertThat(adminResult.content.map { it.id })
            .containsExactlyInAnyOrder(spaceAlpha.id, spaceBeta.id, spaceGamma.id, spaceDelta.id)
        assertThat(adminResult.totalElements).isEqualTo(4L)
    }

    @Test
    @DisplayName("가격 필터 minPrice=6000 검색 시 10000원인 세미나실 베타 1건만 반환한다")
    fun searchSpaces_minPriceFilter() {
        val pageable: Pageable = PageRequest.of(0, 10)

        val result: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                6000L,
                null,
                null,
                null,
                NO_EXCLUSION,
                pageable,
            )
        assertThat(result.content.map { it.id })
            .containsExactly(spaceBeta.id)
    }

    @Test
    @DisplayName("가격 필터 maxPrice=4000 검색 시 ACTIVE 공간 중 100원인 스튜디오 감마 1건만 반환한다")
    fun searchSpaces_maxPriceFilter() {
        val pageable: Pageable = PageRequest.of(0, 10)

        val result: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                4000L,
                null,
                null,
                NO_EXCLUSION,
                pageable,
            )
        assertThat(result.content.map { it.id })
            .containsExactly(spaceGamma.id)
    }

    @Test
    @DisplayName("영업시간 필터 09:00~18:00 검색 시 10시 오픈인 세미나실 베타를 제외한 2건을 반환한다")
    fun searchSpaces_operatingHours_09to18() {
        val pageable: Pageable = PageRequest.of(0, 10)

        val result: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                NO_EXCLUSION,
                pageable,
            )
        assertThat(result.content.map { it.id })
            .containsExactlyInAnyOrder(spaceAlpha.id, spaceGamma.id)
        assertThat(result.totalElements).isEqualTo(2L)
    }

    @Test
    @DisplayName("영업시간 필터 10:00~12:00 검색 시 모든 ACTIVE 공간 3건이 정상 반환된다")
    fun searchSpaces_operatingHours_10to12() {
        val pageable: Pageable = PageRequest.of(0, 10)

        val result: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                LocalTime.of(10, 0),
                LocalTime.of(12, 0),
                NO_EXCLUSION,
                pageable,
            )
        assertThat(result.content.map { it.id })
            .containsExactlyInAnyOrder(spaceAlpha.id, spaceBeta.id, spaceGamma.id)
        assertThat(result.totalElements).isEqualTo(3L)
    }

    @Test
    @DisplayName("영업시간 필터 18:00~20:00 검색 시 18시 마감인 알파/감마를 제외하고 야간 영업하는 세미나실 베타 1건만 반환한다")
    fun searchSpaces_operatingHours_18to20() {
        val pageable: Pageable = PageRequest.of(0, 10)

        val result: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                LocalTime.of(18, 0),
                LocalTime.of(20, 0),
                NO_EXCLUSION,
                pageable,
            )
        assertThat(result.content.map { it.id })
            .containsExactly(spaceBeta.id)
        assertThat(result.totalElements).isEqualTo(1L)
    }

    @Test
    @DisplayName("점유 슬롯 제외 ID 목록이 주어지면 해당 공간이 결과에서 제외된다")
    fun searchSpaces_excludedSpaceIds() {
        val pageable: Pageable = PageRequest.of(0, 10)
        val excluded = listOf(spaceAlpha.id!!)

        val result: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                null,
                null,
                excluded,
                pageable,
            )
        assertThat(result.content.map { it.id })
            .containsExactlyInAnyOrder(spaceBeta.id, spaceGamma.id)
        assertThat(result.totalElements).isEqualTo(2L)
    }

    @Test
    @DisplayName("페이징 요청 시 size와 page에 맞게 슬라이스되고 전체 count 및 totalPages가 정확하게 계산된다")
    fun searchSpaces_paginationAndCountQuery() {
        // 1페이지 (size=2)
        val page0: Pageable = PageRequest.of(0, 2)
        val resultPage0: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                null,
                null,
                NO_EXCLUSION,
                page0,
            )
        assertThat(resultPage0.content).hasSize(2)
        assertThat(resultPage0.totalElements).isEqualTo(3L)
        assertThat(resultPage0.totalPages).isEqualTo(2)
        assertThat(resultPage0.number).isEqualTo(0)

        // 2페이지 (size=2)
        val page1: Pageable = PageRequest.of(1, 2)
        val resultPage1: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                null,
                null,
                NO_EXCLUSION,
                page1,
            )
        assertThat(resultPage1.content).hasSize(1)
        assertThat(resultPage1.totalElements).isEqualTo(3L)
        assertThat(resultPage1.totalPages).isEqualTo(2)
        assertThat(resultPage1.number).isEqualTo(1)
    }

    @Test
    @DisplayName("관리자 검색: keyword=판교 + status=ACTIVE 결합 시 INACTIVE인 라운지 델타를 제외하고 ACTIVE 2건만 반환한다")
    fun searchSpacesByNameOrDescription_keywordPangyo_withStatusActive() {
        val pageable: Pageable = PageRequest.of(0, 10)

        val result: Page<Space> =
            spaceRepository.searchSpacesByNameOrDescription(
                "판교",
                SpaceStatus.ACTIVE,
                pageable,
            )

        assertThat(result.content.map { it.id })
            .containsExactlyInAnyOrder(spaceAlpha.id, spaceBeta.id)
        assertThat(result.totalElements).isEqualTo(2L)
    }

    @Test
    @DisplayName("관리자 검색: keyword=판교 + status=INACTIVE 결합 시 ACTIVE인 알파/베타를 제외하고 위치 매칭된 비활성 델타 1건만 반환한다")
    fun searchSpacesByNameOrDescription_keywordPangyo_withStatusInactive() {
        val pageable: Pageable = PageRequest.of(0, 10)

        val result: Page<Space> =
            spaceRepository.searchSpacesByNameOrDescription(
                "판교",
                SpaceStatus.INACTIVE,
                pageable,
            )

        assertThat(result.content.map { it.id })
            .containsExactly(spaceDelta.id)
        assertThat(result.totalElements).isEqualTo(1L)
    }

    @Test
    @DisplayName("공개 검색: keyword + location + 가격 + 영업시간 + 점유 제외 ID 복합 조건 동시 적용 시 정확히 1건만 반환한다")
    fun searchSpaces_combinedFullFilters() {
        val pageable: Pageable = PageRequest.of(0, 10)
        val excluded = listOf(spaceBeta.id!!)

        val result: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                "판교", // keyword: 알파(이름), 베타(설명) 매칭
                "중앙로", // location: 알파 매칭 (베타 테헤란로 제외)
                4000L, // minPrice
                6000L, // maxPrice (알파 5000원 매칭)
                LocalTime.of(9, 0), // startTime
                LocalTime.of(18, 0), // endTime
                excluded, // 점유 제외 목록에 베타 포함
                pageable,
            )

        assertThat(result.content.map { it.id })
            .containsExactly(spaceAlpha.id)
        assertThat(result.totalElements).isEqualTo(1L)
    }

    @Test
    @DisplayName("영업시간 안의 점유 공간과 영업시간 밖의 비점유 공간이 동시에 제외되어 감마 1건만 반환된다")
    fun searchSpaces_combinedOperatingHoursAndExcludedOccupiedSlots() {
        val pageable: Pageable = PageRequest.of(0, 10)
        // 영업시간 09:00~18:00 -> 10시 오픈인 베타(비점유)는 영업시간 불일치로 제외
        // 알파는 영업시간 안이지만 점유 슬롯 제외 ID로 지정되어 제외
        val excludedOccupied = listOf(spaceAlpha.id!!)

        val result: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                excludedOccupied,
                pageable,
            )

        assertThat(result.content.map { it.id })
            .containsExactly(spaceGamma.id)
        assertThat(result.totalElements).isEqualTo(1L)
    }

    @Test
    @DisplayName("공개 검색: 작은 page size(2)와 정렬로 전 페이지를 조회할 때 중복 및 누락 없이 전체 집합이 일치한다")
    fun searchSpaces_paginationWithSortingAndAllPagesIntegrity() {
        val sort: Sort = Sort.by("id").ascending()
        val page0: Pageable = PageRequest.of(0, 2, sort)
        val page1: Pageable = PageRequest.of(1, 2, sort)

        val resultPage0: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                null,
                null,
                NO_EXCLUSION,
                page0,
            )
        val resultPage1: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                null,
                null,
                NO_EXCLUSION,
                page1,
            )

        assertThat(resultPage0.content).hasSize(2)
        assertThat(resultPage1.content).hasSize(1)
        assertThat(resultPage0.totalElements).isEqualTo(3L)
        assertThat(resultPage0.totalPages).isEqualTo(2)

        val page0Ids: List<Long?> = resultPage0.content.map { it.id }
        val page1Ids: List<Long?> = resultPage1.content.map { it.id }

        // 페이지 간 중복 ID 없음
        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids)

        // 전 페이지 합집합이 전체 ACTIVE 3건(알파, 베타, 감마)과 일치
        val combinedIds = ArrayList<Long?>(page0Ids)
        combinedIds.addAll(page1Ids)
        assertThat(combinedIds)
            .containsExactlyInAnyOrder(spaceAlpha.id, spaceBeta.id, spaceGamma.id)
    }

    @Test
    @DisplayName("관리자 검색: keyword=판교 조건에서 page size(2) 조회 시 필터 count(3) 및 전 페이지 ID가 일치한다")
    fun searchSpacesByNameOrDescription_paginationAndFilterCount() {
        val sort: Sort = Sort.by("id").ascending()
        val page0: Pageable = PageRequest.of(0, 2, sort)
        val page1: Pageable = PageRequest.of(1, 2, sort)

        val resultPage0: Page<Space> =
            spaceRepository.searchSpacesByNameOrDescription(
                "판교",
                null,
                page0,
            )
        val resultPage1: Page<Space> =
            spaceRepository.searchSpacesByNameOrDescription(
                "판교",
                null,
                page1,
            )

        assertThat(resultPage0.totalElements).isEqualTo(3L)
        assertThat(resultPage0.totalPages).isEqualTo(2)
        assertThat(resultPage0.content).hasSize(2)
        assertThat(resultPage1.content).hasSize(1)

        val page0Ids: List<Long?> = resultPage0.content.map { it.id }
        val page1Ids: List<Long?> = resultPage1.content.map { it.id }

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids)

        val combinedIds = ArrayList<Long?>(page0Ids)
        combinedIds.addAll(page1Ids)
        // 전체 3건 (알파[이름], 베타[설명], 델타[위치·INACTIVE])
        assertThat(combinedIds)
            .containsExactlyInAnyOrder(spaceAlpha.id, spaceBeta.id, spaceDelta.id)
    }

    @Test
    @DisplayName("관리자 검색: 선택 상태(ACTIVE) + keyword=판교 조건에서 page size(1) 조회 시 count(2) 및 전 페이지 ID가 일치한다")
    fun searchSpacesByNameOrDescription_adminActiveKeywordPagination() {
        val sort: Sort = Sort.by("id").ascending()
        val page0: Pageable = PageRequest.of(0, 1, sort)
        val page1: Pageable = PageRequest.of(1, 1, sort)

        val resultPage0: Page<Space> =
            spaceRepository.searchSpacesByNameOrDescription(
                "판교",
                SpaceStatus.ACTIVE,
                page0,
            )
        val resultPage1: Page<Space> =
            spaceRepository.searchSpacesByNameOrDescription(
                "판교",
                SpaceStatus.ACTIVE,
                page1,
            )

        assertThat(resultPage0.totalElements).isEqualTo(2L)
        assertThat(resultPage0.totalPages).isEqualTo(2)
        assertThat(resultPage0.content).hasSize(1)
        assertThat(resultPage1.content).hasSize(1)

        val page0Ids: List<Long?> = resultPage0.content.map { it.id }
        val page1Ids: List<Long?> = resultPage1.content.map { it.id }

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids)

        val combinedIds = ArrayList<Long?>(page0Ids)
        combinedIds.addAll(page1Ids)
        // INACTIVE 델타 제외된 알파(이름), 베타(설명) 2건
        assertThat(combinedIds)
            .containsExactlyInAnyOrder(spaceAlpha.id, spaceBeta.id)
    }

    @Test
    @DisplayName("공개 검색: maxPrice=6000 필터 조건에서 page size(1) 조회 시 count(2) 및 전 페이지 ID가 일치한다")
    fun searchSpaces_publicFilterPaginationAndCount() {
        val sort: Sort = Sort.by("id").ascending()
        val page0: Pageable = PageRequest.of(0, 1, sort)
        val page1: Pageable = PageRequest.of(1, 1, sort)

        val resultPage0: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                6000L,
                null,
                null,
                NO_EXCLUSION,
                page0,
            )
        val resultPage1: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                6000L,
                null,
                null,
                NO_EXCLUSION,
                page1,
            )

        assertThat(resultPage0.totalElements).isEqualTo(2L)
        assertThat(resultPage0.totalPages).isEqualTo(2)
        assertThat(resultPage0.content).hasSize(1)
        assertThat(resultPage1.content).hasSize(1)

        val page0Ids: List<Long?> = resultPage0.content.map { it.id }
        val page1Ids: List<Long?> = resultPage1.content.map { it.id }

        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids)

        val combinedIds = ArrayList<Long?>(page0Ids)
        combinedIds.addAll(page1Ids)
        // 알파(5000원)와 감마(100원) 2건 매칭, 베타(10000원) 제외, 델타(INACTIVE) 제외
        assertThat(combinedIds)
            .containsExactlyInAnyOrder(spaceAlpha.id, spaceGamma.id)
    }

    @Test
    @DisplayName("Space 저장 후 flush 및 clear 후 재조회 시 모든 필드가 정상 유지된다")
    fun saveAndFlushAndClear_roundTrip() {
        val newSpace =
            Space
                .builder()
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
                .build()

        val saved = spaceRepository.save(newSpace)
        entityManager.flush()
        entityManager.clear()

        val found = spaceRepository.findById(saved.id!!).orElseThrow()
        assertThat(found.id).isEqualTo(saved.id)
        assertThat(found.name).isEqualTo("라운드트립 공간")
        assertThat(found.location).isEqualTo("서울시 강남구")
        assertThat(found.description).isEqualTo("라운드트립 테스트 설명")
        assertThat(found.capacity).isEqualTo(12)
        assertThat(found.pricePerSlot).isEqualTo(15000L)
        assertThat(found.imagePath).isEqualTo("/images/roundtrip.jpg")
        assertThat(found.openingTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(found.closingTime).isEqualTo(LocalTime.of(20, 0))
        assertThat(found.status).isEqualTo(SpaceStatus.ACTIVE)
        assertThat(found.version).isEqualTo(0)
    }

    @Test
    @DisplayName("em.getReference 프록시는 비ID 프로퍼티 접근 전까지 미초기화 상태이고 접근 시 정상 초기화된다")
    fun getReference_proxyUninitializedUntilNonIdPropertyAccess() {
        entityManager.flush()
        entityManager.clear()

        val id = spaceAlpha.id!!
        val proxy = entityManager.getReference(Space::class.java, id)

        // 프록시 생성 직후 미초기화 상태 검증 (id 조회는 프록시를 초기화하지 않음)
        assertThat(Hibernate.isInitialized(proxy)).isFalse()
        assertThat(proxy.id).isEqualTo(id)
        assertThat(Hibernate.isInitialized(proxy)).isFalse()

        // 첫 비ID 프로퍼티 접근 시 초기화 검증
        assertThat(proxy.name).isEqualTo("판교 회의실 알파")
        assertThat(Hibernate.isInitialized(proxy)).isTrue()
    }

    companion object {
        private val NO_EXCLUSION = listOf(-1L)
    }
}
