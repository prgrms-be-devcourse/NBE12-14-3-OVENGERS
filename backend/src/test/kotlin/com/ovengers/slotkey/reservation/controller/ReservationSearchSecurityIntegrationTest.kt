package com.ovengers.slotkey.reservation.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.auth.repository.AuthSessionRepository
import com.ovengers.slotkey.auth.repository.RefreshTokenRepository
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashMap

@AutoConfigureMockMvc
@Transactional
class ReservationSearchSecurityIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    private lateinit var authSessionRepository: AuthSessionRepository

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    private lateinit var memberA: Member
    private lateinit var memberB: Member
    private lateinit var admin: Member
    private lateinit var tokenA: String
    private lateinit var tokenB: String
    private lateinit var tokenAdmin: String

    private val resA: MutableMap<String, Reservation> = LinkedHashMap()
    private val resB: MutableMap<String, Reservation> = LinkedHashMap()

    @BeforeEach
    fun setUp() {
        reservationStatusHistoryRepository.deleteAllInBatch()
        reservationRepository.deleteAllInBatch()
        spaceRepository.deleteAllInBatch()
        refreshTokenRepository.deleteAllInBatch()
        authSessionRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
        resA.clear()
        resB.clear()

        // 1. 회원 등록
        memberA = memberRepository.save(Member("userA@slotkey.test", "회원A"))
        memberB = memberRepository.save(Member("userB@slotkey.test", "회원B"))
        admin = Member("admin@slotkey.test", "관리자")
        ReflectionTestUtils.setField(admin, "role", MemberRole.ADMIN)
        admin = memberRepository.save(admin)

        tokenA = issueSessionBoundAccessToken(memberA, jwtProvider)
        tokenB = issueSessionBoundAccessToken(memberB, jwtProvider)
        tokenAdmin = issueSessionBoundAccessToken(admin, jwtProvider)

        // 2. 공간 등록 (S1 ~ S4)
        val spaceS1: Space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("판교 회의실")
                    .location("성남시 분당구")
                    .description("집중하기 좋은 쾌적한 회의실")
                    .capacity(4)
                    .pricePerSlot(5000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(18, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(1)
                    .build(),
            )

        val spaceS2: Space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("강남 라운지")
                    .location("서울시 강남구")
                    .description("라운지 공간")
                    .capacity(10)
                    .pricePerSlot(10000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(18, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(1)
                    .build(),
            )

        val spaceS3: Space =
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

        val spaceS4: Space =
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
                saveRes(
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
            saveRes(
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
            saveRes(
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
                saveRes(
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
                saveRes(
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
            saveRes(
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
            saveRes(
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
            saveRes(
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
                saveRes(
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
                saveRes(
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
    }

    private fun saveRes(
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

    private fun getExpectedIds(
        map: Map<String, Reservation>,
        vararg labels: String,
    ): List<Long> = labels.map { l -> map.getValue(l).getId() }

    private fun extractReservationIds(result: MvcResult): List<Long> {
        val json: String = result.response.getContentAsString(StandardCharsets.UTF_8)
        val root: JsonNode = objectMapper.readTree(json)
        val content: JsonNode = root.path("data").path("content")
        val ids: MutableList<Long> = ArrayList()
        for (item in content) {
            ids.add(item.path("reservationId").asLong())
        }
        return ids
    }

    @Test
    @DisplayName("미인증 사용자가 내 예약 목록을 요청하면 401 Unauthorized와 AUTHENTICATION_REQUIRED를 반환한다")
    fun getReservations_unauthenticated_returns401() {
        mockMvc
            .perform(
                get(RESERVATIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("\$.status").value("FAIL"))
            .andExpect(jsonPath("\$.code").value("AUTHENTICATION_REQUIRED"))
    }

    @Test
    @DisplayName("인증된 회원A가 조건 없이 요청하면 200 OK와 PageResponse 5개 필드 및 3개 페이지 전체 ID를 순서대로 반환한다")
    fun getReservations_authenticated_returns200WithPageResponse() {
        // Page 0 (size=10): A23 ~ A14
        val r0: MvcResult =
            mockMvc
                .perform(
                    get(RESERVATIONS_PATH)
                        .param("page", "0")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("\$.status").value("SUCCESS"))
                .andExpect(jsonPath("\$.code").value("OK"))
                .andExpect(jsonPath("\$.data.page").value(0))
                .andExpect(jsonPath("\$.data.size").value(10))
                .andExpect(jsonPath("\$.data.totalElements").value(23))
                .andExpect(jsonPath("\$.data.totalPages").value(3))
                .andExpect(jsonPath("\$.data.content", hasSize<Any>(10)))
                .andReturn()
        assertThat(extractReservationIds(r0))
            .containsExactlyElementsOf(getExpectedIds(resA, "A23", "A22", "A21", "A20", "A19", "A18", "A17", "A16", "A15", "A14"))

        // Page 1 (size=10): A13 ~ A04 (A12 > A11 동률 id DESC 포함)
        val r1: MvcResult =
            mockMvc
                .perform(
                    get(RESERVATIONS_PATH)
                        .param("page", "1")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("\$.data.page").value(1))
                .andExpect(jsonPath("\$.data.size").value(10))
                .andExpect(jsonPath("\$.data.totalElements").value(23))
                .andExpect(jsonPath("\$.data.totalPages").value(3))
                .andExpect(jsonPath("\$.data.content", hasSize<Any>(10)))
                .andReturn()
        assertThat(extractReservationIds(r1))
            .containsExactlyElementsOf(getExpectedIds(resA, "A13", "A12", "A11", "A10", "A09", "A08", "A07", "A06", "A05", "A04"))

        // Page 2 (size=10): A03 ~ A01 (마지막 페이지 3건)
        val r2: MvcResult =
            mockMvc
                .perform(
                    get(RESERVATIONS_PATH)
                        .param("page", "2")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("\$.data.page").value(2))
                .andExpect(jsonPath("\$.data.size").value(10))
                .andExpect(jsonPath("\$.data.totalElements").value(23))
                .andExpect(jsonPath("\$.data.totalPages").value(3))
                .andExpect(jsonPath("\$.data.content", hasSize<Any>(3)))
                .andReturn()
        assertThat(extractReservationIds(r2))
            .containsExactlyElementsOf(getExpectedIds(resA, "A03", "A02", "A01"))
    }

    @Test
    @DisplayName("요청 파라미터로 타인 memberId를 조작해 전달해도 무시되고 본인(회원A) 예약만 반환되며 회원B 예약은 0개 혼입된다")
    fun getReservations_tamperedMemberIdInQuery_ignoredAndRestrictedToPrincipal() {
        val r: MvcResult =
            mockMvc
                .perform(
                    get(RESERVATIONS_PATH)
                        .param("memberId", memberB.id!!.toString())
                        .param("page", "0")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("\$.data.page").value(0))
                .andExpect(jsonPath("\$.data.totalElements").value(23))
                .andExpect(jsonPath("\$.data.totalPages").value(3))
                .andReturn()
        val actualIds: List<Long> = extractReservationIds(r)
        assertThat(actualIds)
            .containsExactlyElementsOf(getExpectedIds(resA, "A23", "A22", "A21", "A20", "A19", "A18", "A17", "A16", "A15", "A14"))
        val bIds: Set<Long> = HashSet(resB.values.map { it.getId() })
        assertThat(actualIds).doesNotContainAnyElementsOf(bIds)
    }

    @Test
    @DisplayName("앞뒤 공백이 포함된 keyword('  판교  ')와 status=CONFIRMED 복합 검색 시 총 14건 2페이지 전체 ID를 정확히 반환한다")
    fun getReservations_keywordAndStatus_returnsFiltered() {
        // Page 0 (size=10): A23, A22, A12~A05 (10건)
        val r0: MvcResult =
            mockMvc
                .perform(
                    get(RESERVATIONS_PATH)
                        .param("keyword", "  판교  ")
                        .param("status", "CONFIRMED")
                        .param("page", "0")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("\$.data.page").value(0))
                .andExpect(jsonPath("\$.data.size").value(10))
                .andExpect(jsonPath("\$.data.totalElements").value(14))
                .andExpect(jsonPath("\$.data.totalPages").value(2))
                .andExpect(jsonPath("\$.data.content", hasSize<Any>(10)))
                .andReturn()
        assertThat(extractReservationIds(r0))
            .containsExactlyElementsOf(getExpectedIds(resA, "A23", "A22", "A12", "A11", "A10", "A09", "A08", "A07", "A06", "A05"))

        // Page 1 (size=10): A04 ~ A01 (4건)
        val r1: MvcResult =
            mockMvc
                .perform(
                    get(RESERVATIONS_PATH)
                        .param("keyword", "  판교  ")
                        .param("status", "CONFIRMED")
                        .param("page", "1")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("\$.data.page").value(1))
                .andExpect(jsonPath("\$.data.size").value(10))
                .andExpect(jsonPath("\$.data.totalElements").value(14))
                .andExpect(jsonPath("\$.data.totalPages").value(2))
                .andExpect(jsonPath("\$.data.content", hasSize<Any>(4)))
                .andReturn()
        assertThat(extractReservationIds(r1))
            .containsExactlyElementsOf(getExpectedIds(resA, "A04", "A03", "A02", "A01"))
    }

    @Test
    @DisplayName("공백만 있는 keyword('   ')는 무시되어 전체 23건이 반환된다")
    fun getReservations_blankKeyword_treatedAsNull() {
        mockMvc
            .perform(
                get(RESERVATIONS_PATH)
                    .param("keyword", "   ")
                    .param("page", "0")
                    .param("size", "10")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("\$.data.page").value(0))
            .andExpect(jsonPath("\$.data.size").value(10))
            .andExpect(jsonPath("\$.data.totalElements").value(23))
            .andExpect(jsonPath("\$.data.totalPages").value(3))
    }

    @Test
    @DisplayName("잘못된 status enum 값 전달 시 400 Bad Request와 VALIDATION_FAILED를 반환한다")
    fun getReservations_invalidStatus_returns400() {
        mockMvc
            .perform(
                get(RESERVATIONS_PATH)
                    .param("status", "UNKNOWN_STATUS")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.status").value("FAIL"))
            .andExpect(jsonPath("\$.code").value("VALIDATION_FAILED"))
    }

    @Test
    @DisplayName("일치하는 예약이 없는 조건 검색 시 200 OK와 content=[], totalElements=0을 반환한다")
    fun getReservations_unmatchedFilter_returns200WithEmptyPage() {
        mockMvc
            .perform(
                get(RESERVATIONS_PATH)
                    .param("keyword", "미등록")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("\$.data.page").value(0))
            .andExpect(jsonPath("\$.data.totalElements").value(0))
            .andExpect(jsonPath("\$.data.totalPages").value(0))
            .andExpect(jsonPath("\$.data.content", hasSize<Any>(0)))
    }

    @Test
    @DisplayName("회원B 인증 토큰으로 요청하면 회원B 본인 예약 5건만 반환되며 회원A 예약은 0개 혼입된다")
    fun getReservations_memberB_returnsOnlyMemberBReservations() {
        val r: MvcResult =
            mockMvc
                .perform(
                    get(RESERVATIONS_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("\$.data.page").value(0))
                .andExpect(jsonPath("\$.data.totalElements").value(5))
                .andExpect(jsonPath("\$.data.totalPages").value(1))
                .andExpect(jsonPath("\$.data.content", hasSize<Any>(5)))
                .andReturn()
        val actualIds: List<Long> = extractReservationIds(r)
        assertThat(actualIds)
            .containsExactlyElementsOf(getExpectedIds(resB, "B05", "B04", "B03", "B02", "B01"))
        val aIds: Set<Long> = HashSet(resA.values.map { it.getId() })
        assertThat(actualIds).doesNotContainAnyElementsOf(aIds)
    }

    @Test
    @DisplayName("ADMIN 권한 사용자라도 /api/v1/reservations 경로에서는 본인의 예약(0건)만 반환된다")
    fun getReservations_admin_returnsOnlyAdminReservations() {
        mockMvc
            .perform(
                get(RESERVATIONS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAdmin)
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("\$.data.page").value(0))
            .andExpect(jsonPath("\$.data.totalElements").value(0))
            .andExpect(jsonPath("\$.data.totalPages").value(0))
            .andExpect(jsonPath("\$.data.content", hasSize<Any>(0)))
    }

    @AfterEach
    fun tearDown() {
        reservationStatusHistoryRepository.deleteAllInBatch()
        reservationRepository.deleteAllInBatch()
        spaceRepository.deleteAllInBatch()
        refreshTokenRepository.deleteAllInBatch()
        authSessionRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
    }

    companion object {
        private val RESERVATIONS_PATH: String = "/api/v1/reservations"
    }
}
