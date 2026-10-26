package com.ovengers.slotkey.stats.service

import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.stats.entity.RankingMetric
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.SpaceRankingSnapshot
import com.ovengers.slotkey.stats.repository.SpaceRankingSnapshotRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import com.ovengers.slotkey.support.SettableClockConfig
import com.ovengers.slotkey.support.SettableClockConfig.SettableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

@Import(SettableClockConfig::class)
class SpaceRankingMissingSpaceQueryIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var queryService: SpaceRankingQueryService

    @Autowired
    private lateinit var snapshotRepository: SpaceRankingSnapshotRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var settableClock: SettableClock

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val testPeriod = RankingPeriodType.DAILY
    private val testDate = LocalDate.of(2027, 3, 1)
    private val computedAt = LocalDateTime.of(2027, 3, 2, 0, 1, 0)

    @BeforeEach
    fun setUp() {
        // 확정 이후 시점으로 Clock 설정 (2027-03-02 00:02 KST)
        settableClock.set(ZonedDateTime.of(2027, 3, 2, 0, 2, 0, 0, SettableClockConfig.KST))
        cleanSnapshots()
        cleanFixtureSpaces()
    }

    @AfterEach
    fun tearDown() {
        cleanSnapshots()
        cleanFixtureSpaces()
    }

    private fun cleanSnapshots() {
        jdbcTemplate.update("DELETE FROM space_ranking_snapshot")
    }

    private fun cleanFixtureSpaces() {
        jdbcTemplate.update("DELETE FROM spaces WHERE name = '실제 존재하는 회의실'")
    }

    @Test
    @DisplayName("확정 스냅샷에 기록된 spaceId가 실제 spaces 테이블에 존재하지 않으면 spaces[row.spaceId]!!에서 NullPointerException이 발생하여 현재 실패 경계가 고정된다")
    fun getSpaceRanking_whenReferencedSpaceMissing_throwsNullPointerException() {
        val missingSpaceId = 999_999_999L
        assertThat(spaceRepository.findById(missingSpaceId)).isEmpty

        // FK가 없는 독립 집계 테이블이므로 참조 공간이 없더라도 스냅샷 행이 정상 적재 가능함을 입증
        val orphanSnapshot =
            SpaceRankingSnapshot(
                testPeriod,
                testDate,
                missingSpaceId,
                "서울 강남",
                50000L,
                5,
                10,
                computedAt,
            )
        snapshotRepository.save(orphanSnapshot)
        assertThat(snapshotRepository.findByPeriodTypeAndPeriodStart(testPeriod, testDate)).hasSize(1)

        // 조회 호출 시 spaces[row.spaceId]!! 연산자에서 Kotlin NullPointerException 발생 확인
        // (임의 fallback이나 빈 결과로 대체하지 않고 현재 예외 경계를 그대로 보존)
        assertThatThrownBy {
            queryService.getSpaceRanking(
                testPeriod,
                testDate,
                RankingMetric.NET_REVENUE,
                null,
                10,
            )
        }.isInstanceOf(NullPointerException::class.java)
    }

    @Test
    @DisplayName("대조군: 참조 공간이 실제 spaces 테이블에 온전히 존재하는 경우 NullPointerException 없이 정상적으로 랭킹 응답이 반환된다")
    fun getSpaceRanking_whenReferencedSpaceExists_succeeds() {
        // 실제 공간 엔티티 생성 및 저장
        val realSpace =
            Space
                .builder()
                .name("실제 존재하는 회의실")
                .description("테스트 공간 설명")
                .location("서울 강남")
                .capacity(10)
                .pricePerSlot(5000L)
                .status(SpaceStatus.ACTIVE)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(22, 0))
                .build()
        val savedSpace = spaceRepository.save(realSpace)
        val validSpaceId = savedSpace.id!!

        val validSnapshot =
            SpaceRankingSnapshot(
                testPeriod,
                testDate,
                validSpaceId,
                "서울 강남",
                100000L,
                10,
                20,
                computedAt,
            )
        snapshotRepository.save(validSnapshot)

        val response =
            queryService.getSpaceRanking(
                testPeriod,
                testDate,
                RankingMetric.NET_REVENUE,
                null,
                10,
            )

        assertThat(response).isNotNull
        assertThat(response.items).hasSize(1)
        val item = response.items[0]
        assertThat(item.spaceId).isEqualTo(validSpaceId)
        assertThat(item.spaceName).isEqualTo("실제 존재하는 회의실")
        assertThat(item.location).isEqualTo("서울 강남")
        assertThat(item.netRevenue).isEqualTo(100000L)
        assertThat(item.reservationCount).isEqualTo(10)
        assertThat(item.slotCount).isEqualTo(20)
    }
}
