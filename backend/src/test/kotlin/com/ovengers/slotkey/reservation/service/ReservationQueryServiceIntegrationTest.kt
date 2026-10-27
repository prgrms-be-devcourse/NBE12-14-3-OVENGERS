package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.dto.request.ReservationSearchCondition
import com.ovengers.slotkey.reservation.dto.response.ReservationListResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashMap

@Transactional
class ReservationQueryServiceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var reservationQueryService: ReservationQueryService

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var memberRepository: MemberRepository

    private lateinit var memberA: Member
    private lateinit var memberB: Member
    private lateinit var memberC: Member
    private lateinit var admin: Member

    private lateinit var spaceS1: Space
    private lateinit var spaceS2: Space
    private lateinit var spaceS3: Space
    private lateinit var spaceS4: Space

    private val resA: MutableMap<String, Reservation> = LinkedHashMap()
    private val resB: MutableMap<String, Reservation> = LinkedHashMap()

    @BeforeEach
    fun setUp() {
        reservationStatusHistoryRepository.deleteAllInBatch()
        reservationRepository.deleteAllInBatch()
        spaceRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
        resA.clear()
        resB.clear()

        // 1. 회원 저장
        memberA = memberRepository.save(Member("memberA@slotkey.test", "회원A"))
        memberB = memberRepository.save(Member("memberB@slotkey.test", "회원B"))
        memberC = memberRepository.save(Member("memberC@slotkey.test", "회원C"))
        admin = memberRepository.save(Member("admin@slotkey.test", "관리자"))

        // 2. 공간 저장 (S1 ~ S4)
        spaceS1 =
            spaceRepository.save(
                Space
                    .builder()
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
                    .build(),
            )

        spaceS2 =
            spaceRepository.save(
                Space
                    .builder()
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
                    .build(),
            )

        spaceS3 =
            spaceRepository.save(
                Space
                    .builder()
                    .name("야간 판교 오피스")
                    .location("성남시 분당구")
                    .description("야간 작업 전용")
                    .capacity(6)
                    .pricePerSlot(7000L)
                    .openingTime(LocalTime.of(18, 0))
                    .closingTime(LocalTime.of(23, 0))
                    .status(SpaceStatus.INACTIVE) // 비활성 공간
                    .version(1)
                    .build(),
            )

        spaceS4 =
            spaceRepository.save(
                Space
                    .builder()
                    .name("하남 스튜디오")
                    .location("성남시 판교역로") // 위치에만 '판교'
                    .description("판교 테크팀 추천 대형 스튜디오") // 설명에만 '판교'
                    .capacity(8)
                    .pricePerSlot(6000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(18, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(1)
                    .build(),
            )

        // 3. 예약 데이터 생성 (순번이 커질수록 startTime이 늦음, A11과 A12만 같은 startTime)
        val now: LocalDateTime = LocalDateTime.now()

        // A01~A10 (S1, CONFIRMED)
        for (i in 1..10) {
            val label: String = String.format("A%02d", i)
            val start: LocalDateTime = LocalDateTime.of(2026, 10, 1, 8, 0).plusHours(i.toLong())
            resA.put(
                label,
                saveReservation(
                    memberA.id!!,
                    spaceS1.id!!,
                    start,
                    start.plusHours(1),
                    ReservationStatus.CONFIRMED,
                    null,
                    null,
                    null,
                    now,
                ),
            )
        }

        // A11, A12: 같은 startTime (2026-10-02 11:00:00). A를 순번 순서로 저장하여 A12.id > A11.id
        val sameStart: LocalDateTime = LocalDateTime.of(2026, 10, 2, 11, 0)
        resA.put(
            "A11",
            saveReservation(
                memberA.id!!,
                spaceS1.id!!,
                sameStart,
                sameStart.plusHours(1),
                ReservationStatus.CONFIRMED,
                null,
                null,
                null,
                now,
            ),
        )
        resA.put(
            "A12",
            saveReservation(
                memberA.id!!,
                spaceS1.id!!,
                sameStart,
                sameStart.plusHours(1),
                ReservationStatus.CONFIRMED,
                null,
                null,
                null,
                now,
            ),
        )

        // A13~A17 (S1, CANCELLED)
        for (i in 13..17) {
            val label: String = String.format("A%02d", i)
            val start: LocalDateTime = LocalDateTime.of(2026, 10, 2, 13, 0).plusHours((i - 13).toLong())
            resA.put(
                label,
                saveReservation(
                    memberA.id!!,
                    spaceS1.id!!,
                    start,
                    start.plusHours(1),
                    ReservationStatus.CANCELLED,
                    null,
                    null,
                    now,
                    now,
                ),
            )
        }

        // A18~A20 (S2, IN_USE)
        for (i in 18..20) {
            val label: String = String.format("A%02d", i)
            val start: LocalDateTime = LocalDateTime.of(2026, 10, 3, 10, 0).plusHours((i - 18).toLong())
            resA.put(
                label,
                saveReservation(
                    memberA.id!!,
                    spaceS2.id!!,
                    start,
                    start.plusHours(1),
                    ReservationStatus.IN_USE,
                    null,
                    now,
                    null,
                    now,
                ),
            )
        }

        // A21 (S4, CANCELLED)
        val startA21: LocalDateTime = LocalDateTime.of(2026, 10, 3, 14, 0)
        resA.put(
            "A21",
            saveReservation(
                memberA.id!!,
                spaceS4.id!!,
                startA21,
                startA21.plusHours(1),
                ReservationStatus.CANCELLED,
                null,
                null,
                now,
                now,
            ),
        )

        // A22~A23 (S3 INACTIVE, CONFIRMED)
        val startA22: LocalDateTime = LocalDateTime.of(2026, 10, 3, 18, 0)
        resA.put(
            "A22",
            saveReservation(
                memberA.id!!,
                spaceS3.id!!,
                startA22,
                startA22.plusHours(1),
                ReservationStatus.CONFIRMED,
                null,
                null,
                null,
                now,
            ),
        )
        val startA23: LocalDateTime = LocalDateTime.of(2026, 10, 3, 19, 0)
        resA.put(
            "A23",
            saveReservation(
                memberA.id!!,
                spaceS3.id!!,
                startA23,
                startA23.plusHours(1),
                ReservationStatus.CONFIRMED,
                null,
                null,
                null,
                now,
            ),
        )

        // B01~B03 (S1, CONFIRMED)
        for (i in 1..3) {
            val label: String = String.format("B%02d", i)
            val start: LocalDateTime = LocalDateTime.of(2026, 10, 1, 9, 0).plusHours(i.toLong())
            resB.put(
                label,
                saveReservation(
                    memberB.id!!,
                    spaceS1.id!!,
                    start,
                    start.plusHours(1),
                    ReservationStatus.CONFIRMED,
                    null,
                    null,
                    null,
                    now,
                ),
            )
        }
        // B04~B05 (S4, CONFIRMED)
        for (i in 4..5) {
            val label: String = String.format("B%02d", i)
            val start: LocalDateTime = LocalDateTime.of(2026, 10, 1, 9, 0).plusHours(i.toLong())
            resB.put(
                label,
                saveReservation(
                    memberB.id!!,
                    spaceS4.id!!,
                    start,
                    start.plusHours(1),
                    ReservationStatus.CONFIRMED,
                    null,
                    null,
                    null,
                    now,
                ),
            )
        }

        // A12.id > A11.id 확인
        assertThat(resA.getValue("A12").getId()).isGreaterThan(resA.getValue("A11").getId())
    }

    private fun saveReservation(
        memberId: Long,
        spaceId: Long,
        start: LocalDateTime,
        end: LocalDateTime,
        status: ReservationStatus,
        holdExpiresAt: LocalDateTime?,
        checkedInAt: LocalDateTime?,
        cancelledAt: LocalDateTime?,
        createdAt: LocalDateTime,
    ): Reservation {
        val res: Reservation =
            Reservation
                .builder()
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
                .build()
        return reservationRepository.save(res)
    }

    private fun getIds(vararg labels: String): List<Long> = labels.map { l -> resA.getValue(l).getId() }

    @Test
    @DisplayName("조건 없음(또는 null, 빈 문자열, 공백만 입력): 전체 23건 3페이지 내림차순 조회 및 합집합 전수 일치, B 예약 미혼입")
    fun getMyReservations_unconditional_returns23ItemsAcross3Pages() {
        val blankKeywords = arrayOf(null, "", "   ")
        for (kw in blankKeywords) {
            val condition: ReservationSearchCondition = ReservationSearchCondition(null, kw)

            // Page 0 (size=10): A23 ~ A14
            val p0: Page<ReservationListResponse> =
                reservationQueryService.getMyReservations(
                    memberA.id!!,
                    condition,
                    PageRequest.of(0, 10),
                )
            assertThat(p0.totalElements).isEqualTo(23)
            assertThat(p0.totalPages).isEqualTo(3)
            assertThat(p0.content).hasSize(10)
            val p0Expected: List<Long> = getIds("A23", "A22", "A21", "A20", "A19", "A18", "A17", "A16", "A15", "A14")
            assertThat(p0.content.map { it.reservationId })
                .containsExactlyElementsOf(p0Expected)

            // Page 1 (size=10): A13 ~ A04
            val p1: Page<ReservationListResponse> =
                reservationQueryService.getMyReservations(
                    memberA.id!!,
                    condition,
                    PageRequest.of(1, 10),
                )
            assertThat(p1.totalElements).isEqualTo(23)
            assertThat(p1.totalPages).isEqualTo(3)
            assertThat(p1.content).hasSize(10)
            val p1Expected: List<Long> = getIds("A13", "A12", "A11", "A10", "A09", "A08", "A07", "A06", "A05", "A04")
            assertThat(p1.content.map { it.reservationId })
                .containsExactlyElementsOf(p1Expected)

            // Page 2 (size=10): A03 ~ A01
            val p2: Page<ReservationListResponse> =
                reservationQueryService.getMyReservations(
                    memberA.id!!,
                    condition,
                    PageRequest.of(2, 10),
                )
            assertThat(p2.totalElements).isEqualTo(23)
            assertThat(p2.totalPages).isEqualTo(3)
            assertThat(p2.content).hasSize(3)
            val p2Expected: List<Long> = getIds("A03", "A02", "A01")
            assertThat(p2.content.map { it.reservationId })
                .containsExactlyElementsOf(p2Expected)

            // 합집합 23건 중복/누락 없음 & B 예약 ID 0개 혼입
            val allCollected: MutableList<Long> = ArrayList()
            allCollected.addAll(p0.content.map { it.reservationId })
            allCollected.addAll(p1.content.map { it.reservationId })
            allCollected.addAll(p2.content.map { it.reservationId })
            assertThat(allCollected).hasSize(23)
            assertThat(HashSet(allCollected)).hasSize(23) // 중복 없음
            val bIds: Set<Long> = HashSet(resB.values.map { it.getId() })
            assertThat(allCollected).doesNotContainAnyElementsOf(bIds)
        }
    }

    @Test
    @DisplayName("동일 startTime인 A11과 A12의 id DESC 정렬 단언 (A12.id > A11.id이므로 A12가 A11보다 먼저 반환됨)")
    fun getMyReservations_tieBreakingByIdDesc() {
        val p1: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                ReservationSearchCondition(null, null),
                PageRequest.of(1, 10),
            )

        val returnedIds: List<Long> = p1.content.map { it.reservationId }
        val idxA12: Int = returnedIds.indexOf(resA.getValue("A12").getId())
        val idxA11: Int = returnedIds.indexOf(resA.getValue("A11").getId())

        assertThat(idxA12).isNotNegative()
        assertThat(idxA11).isNotNegative()
        assertThat(idxA12).isLessThan(idxA11) // A12가 먼저 나옴
    }

    @Test
    @DisplayName("keyword=판교(앞뒤 공백 포함): 위치/설명에만 판교가 있는 S4 제외, INACTIVE S3 포함하여 19건 반환")
    fun getMyReservations_keywordPangyo_returns19Items() {
        val keywords = arrayOf("판교", "  판교  ")
        for (kw in keywords) {
            val condition: ReservationSearchCondition = ReservationSearchCondition(null, kw)

            val p0: Page<ReservationListResponse> =
                reservationQueryService.getMyReservations(
                    memberA.id!!,
                    condition,
                    PageRequest.of(0, 10),
                )
            assertThat(p0.totalElements).isEqualTo(19)
            assertThat(p0.totalPages).isEqualTo(2)
            assertThat(p0.content).hasSize(10)
            val p0Expected: List<Long> = getIds("A23", "A22", "A17", "A16", "A15", "A14", "A13", "A12", "A11", "A10")
            assertThat(p0.content.map { it.reservationId })
                .containsExactlyElementsOf(p0Expected)

            val p1: Page<ReservationListResponse> =
                reservationQueryService.getMyReservations(
                    memberA.id!!,
                    condition,
                    PageRequest.of(1, 10),
                )
            assertThat(p1.totalElements).isEqualTo(19)
            assertThat(p1.totalPages).isEqualTo(2)
            assertThat(p1.content).hasSize(9)
            val p1Expected: List<Long> = getIds("A09", "A08", "A07", "A06", "A05", "A04", "A03", "A02", "A01")
            assertThat(p1.content.map { it.reservationId })
                .containsExactlyElementsOf(p1Expected)

            // S4(A21)는 위치/설명에만 판교가 있으므로 제외됨
            assertThat(p0.content.map { it.reservationId })
                .doesNotContain(resA.getValue("A21").getId())
            assertThat(p1.content.map { it.reservationId })
                .doesNotContain(resA.getValue("A21").getId())
        }
    }

    @Test
    @DisplayName("status=CONFIRMED: 총 14건 2페이지 반환 (A23, A22, A12~A01)")
    fun getMyReservations_statusConfirmed_returns14Items() {
        val condition: ReservationSearchCondition = ReservationSearchCondition(ReservationStatus.CONFIRMED, null)

        val p0: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                condition,
                PageRequest.of(0, 10),
            )
        assertThat(p0.totalElements).isEqualTo(14)
        assertThat(p0.totalPages).isEqualTo(2)
        assertThat(p0.content).hasSize(10)
        val p0Expected: List<Long> = getIds("A23", "A22", "A12", "A11", "A10", "A09", "A08", "A07", "A06", "A05")
        assertThat(p0.content.map { it.reservationId })
            .containsExactlyElementsOf(p0Expected)

        val p1: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                condition,
                PageRequest.of(1, 10),
            )
        assertThat(p1.content).hasSize(4)
        val p1Expected: List<Long> = getIds("A04", "A03", "A02", "A01")
        assertThat(p1.content.map { it.reservationId })
            .containsExactlyElementsOf(p1Expected)
    }

    @Test
    @DisplayName("keyword=판교 + status=CONFIRMED: 총 14건 2페이지 반환 (A23, A22, A12~A01)")
    fun getMyReservations_keywordPangyoAndStatusConfirmed_returns14Items() {
        val condition: ReservationSearchCondition = ReservationSearchCondition(ReservationStatus.CONFIRMED, "판교")

        val p0: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                condition,
                PageRequest.of(0, 10),
            )
        assertThat(p0.totalElements).isEqualTo(14)
        assertThat(p0.totalPages).isEqualTo(2)
        assertThat(p0.content).hasSize(10)
        val p0Expected: List<Long> = getIds("A23", "A22", "A12", "A11", "A10", "A09", "A08", "A07", "A06", "A05")
        assertThat(p0.content.map { it.reservationId })
            .containsExactlyElementsOf(p0Expected)

        val p1: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                condition,
                PageRequest.of(1, 10),
            )
        assertThat(p1.totalElements).isEqualTo(14)
        assertThat(p1.totalPages).isEqualTo(2)
        assertThat(p1.content).hasSize(4)
        val p1Expected: List<Long> = getIds("A04", "A03", "A02", "A01")
        assertThat(p1.content.map { it.reservationId })
            .containsExactlyElementsOf(p1Expected)
    }

    @Test
    @DisplayName("keyword=판교 + status=CANCELLED: 총 5건 1페이지 반환 (A17~A13, S4의 A21은 제외)")
    fun getMyReservations_keywordPangyoAndStatusCancelled_returns5Items() {
        val condition: ReservationSearchCondition = ReservationSearchCondition(ReservationStatus.CANCELLED, "판교")

        val p0: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                condition,
                PageRequest.of(0, 10),
            )
        assertThat(p0.totalElements).isEqualTo(5)
        assertThat(p0.totalPages).isEqualTo(1)
        assertThat(p0.content).hasSize(5)
        val expected: List<Long> = getIds("A17", "A16", "A15", "A14", "A13")
        assertThat(p0.content.map { it.reservationId })
            .containsExactlyElementsOf(expected)
    }

    @Test
    @DisplayName("keyword=라운지 + status=IN_USE: 총 3건 1페이지 반환 (A20~A18)")
    fun getMyReservations_keywordLoungeAndStatusInUse_returns3Items() {
        val condition: ReservationSearchCondition = ReservationSearchCondition(ReservationStatus.IN_USE, "라운지")

        val p0: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                condition,
                PageRequest.of(0, 10),
            )
        assertThat(p0.totalElements).isEqualTo(3)
        assertThat(p0.totalPages).isEqualTo(1)
        assertThat(p0.content).hasSize(3)
        val expected: List<Long> = getIds("A20", "A19", "A18")
        assertThat(p0.content.map { it.reservationId })
            .containsExactlyElementsOf(expected)
    }

    @Test
    @DisplayName("keyword=라운지 + status=CONFIRMED 또는 keyword=미등록: 0건 빈 목록 반환")
    fun getMyReservations_noMatch_returnsEmptyPage() {
        val c1: ReservationSearchCondition = ReservationSearchCondition(ReservationStatus.CONFIRMED, "라운지")
        val p1: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                c1,
                PageRequest.of(0, 10),
            )
        assertThat(p1.totalElements).isEqualTo(0)
        assertThat(p1.totalPages).isEqualTo(0)
        assertThat(p1.content).isEmpty()

        val c2: ReservationSearchCondition = ReservationSearchCondition(null, "미등록")
        val p2: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                c2,
                PageRequest.of(0, 10),
            )
        assertThat(p2.totalElements).isEqualTo(0)
        assertThat(p2.totalPages).isEqualTo(0)
        assertThat(p2.content).isEmpty()
    }

    @Test
    @DisplayName("범위 밖 page=3 요청 시 content=[]이지만 totalElements=23, totalPages=3은 보존됨")
    fun getMyReservations_outOfBoundsPage_preservesTotals() {
        val p3: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                ReservationSearchCondition(null, null),
                PageRequest.of(3, 10),
            )
        assertThat(p3.totalElements).isEqualTo(23)
        assertThat(p3.totalPages).isEqualTo(3)
        assertThat(p3.content).isEmpty()
    }

    @Test
    @DisplayName("클라이언트 요청 sort=ASC 무시하고 서버 고정 정렬(startTime DESC, id DESC) 유지 및 size=101은 100으로 제한")
    fun getMyReservations_clientSortIgnoredAndSizeCapped() {
        // 클라이언트가 sort=startTime: ASC를 요청해도 무시되고 DESC로 조회됨
        val p: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                ReservationSearchCondition(null, null),
                PageRequest.of(0, 101, Sort.by(Sort.Direction.ASC, "startTime")),
            )
        assertThat(p.size).isEqualTo(100)
        assertThat(p.content).hasSize(23) // 전체 23개 모두 들어옴
        // 첫 번째 원소는 A23 (가장 늦은 시간), 마지막은 A01 (가장 이른 시간)
        assertThat(p.content.get(0).reservationId).isEqualTo(resA.getValue("A23").getId())
        assertThat(p.content.get(22).reservationId).isEqualTo(resA.getValue("A01").getId())
    }

    @Test
    @DisplayName("공간 이름 변경 시: 새 이름으로 즉시 검색되고 이전 이름으로는 검색되지 않으며 DTO의 spaceName도 갱신됨")
    fun getMyReservations_spaceNameUpdateReflectedInSearchAndResponse() {
        // S1 이름을 '판교 회의실' -> '분당 회의실'로 변경
        spaceS1.updateDetail(
            com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest(
                null,
                "분당 회의실",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            ),
        )
        spaceRepository.save(spaceS1)

        // keyword=분당 검색 -> S1의 17건(A17~A01) 반환
        val pBundang: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                ReservationSearchCondition(null, "분당"),
                PageRequest.of(0, 20),
            )
        assertThat(pBundang.totalElements).isEqualTo(17)
        assertThat(pBundang.content.get(0).spaceName).isEqualTo("분당 회의실")

        // keyword=판교 검색 -> S3의 2건(A23, A22)만 반환
        val pPangyo: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                ReservationSearchCondition(null, "판교"),
                PageRequest.of(0, 20),
            )
        assertThat(pPangyo.totalElements).isEqualTo(2)

        // keyword=판교 회의실 (이전 이름) 검색 -> 0건
        val pOld: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                ReservationSearchCondition(null, "판교 회의실"),
                PageRequest.of(0, 20),
            )
        assertThat(pOld.totalElements).isEqualTo(0)
    }

    @Test
    @DisplayName("DTO 필드 검증: date, startTime, endTime, spaceName, spaceLocation, spaceImagePath, totalAmount 등 계약 일치")
    fun getMyReservations_dtoFieldsMatchContract() {
        val page: Page<ReservationListResponse> =
            reservationQueryService.getMyReservations(
                memberA.id!!,
                ReservationSearchCondition(ReservationStatus.IN_USE, "라운지"),
                PageRequest.of(0, 10),
            )

        assertThat(page.content).isNotEmpty()
        val dto: ReservationListResponse = page.content.get(0)
        val expectedEntity: Reservation = resA.getValue("A20")

        assertThat(dto.reservationId).isEqualTo(expectedEntity.getId())
        assertThat(dto.spaceId).isEqualTo(spaceS2.id!!)
        assertThat(dto.spaceName).isEqualTo("강남 라운지")
        assertThat(dto.spaceLocation).isEqualTo("서울시 강남구")
        assertThat(dto.spaceImagePath).isEqualTo("/images/gangnam-lounge.jpg")
        assertThat(dto.status).isEqualTo("IN_USE")
        assertThat(dto.date).isEqualTo(LocalDate.of(2026, 10, 3))
        assertThat(dto.startTime).isEqualTo(expectedEntity.getStartTime())
        assertThat(dto.endTime).isEqualTo(expectedEntity.getEndTime())
        assertThat(dto.totalAmount).isEqualTo(10000)
        assertThat(dto.pricePerSlotSnapshot).isEqualTo(5000)
    }

    @AfterEach
    fun tearDown() {
        reservationStatusHistoryRepository.deleteAllInBatch()
        reservationRepository.deleteAllInBatch()
        spaceRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
    }
}
