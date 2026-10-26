package com.ovengers.slotkey.stats.service

import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.entity.SpaceRankingSnapshot
import com.ovengers.slotkey.stats.repository.SpaceRankingAggregate
import com.ovengers.slotkey.stats.repository.SpaceRankingAggregationRepository
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
import org.mockito.Mockito
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

@Import(SettableClockConfig::class)
class SpaceRankingConfirmAtomicityIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var confirmService: SpaceRankingConfirmService

    @SpyBean
    private lateinit var snapshotRepository: SpaceRankingSnapshotRepository

    @SpyBean
    private lateinit var aggregationRepository: SpaceRankingAggregationRepository

    @Autowired
    private lateinit var settableClock: SettableClock

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private val testPeriodType = RankingPeriodType.DAILY
    private val testDate = LocalDate.of(2026, 3, 10)
    private val periodStart = testDate

    private fun anyDateTime(): LocalDateTime {
        Mockito.any(LocalDateTime::class.java)
        return LocalDateTime.MIN
    }

    @BeforeEach
    fun setUp() {
        settableClock.set(ZonedDateTime.of(2027, 3, 1, 0, 1, 0, 0, SettableClockConfig.KST))
        cleanSnapshots()
    }

    @AfterEach
    fun tearDown() {
        cleanSnapshots()
    }

    private fun cleanSnapshots() {
        jdbcTemplate.update("DELETE FROM space_ranking_snapshot")
    }

    @Test
    @DisplayName("재확정 중 deleteByPeriod 실행 후 실제 DB flush 시 제약조건 위반으로 실패하면 트랜잭션이 롤백되어 기존 확정 행과 값이 모두 온전히 보존된다")
    fun confirm_whenDbFlushFailsAfterDeleteByPeriod_rollsBackAndPreservesOriginalRows() {
        // given: 기존 확정 스냅샷 2건을 미리 커밋 상태로 적재
        val initialComputedAt = LocalDateTime.of(2026, 3, 11, 0, 1, 0)
        val initialRow1 =
            SpaceRankingSnapshot(
                testPeriodType,
                periodStart,
                101L,
                "서울 강남",
                50000L,
                5,
                10,
                initialComputedAt,
            )
        val initialRow2 =
            SpaceRankingSnapshot(
                testPeriodType,
                periodStart,
                102L,
                "서울 서초",
                30000L,
                3,
                6,
                initialComputedAt,
            )
        val savedInitial1 = snapshotRepository.save(initialRow1)
        val savedInitial2 = snapshotRepository.save(initialRow2)

        val initialSnapshots = snapshotRepository.findByPeriodTypeAndPeriodStart(testPeriodType, periodStart)
        assertThat(initialSnapshots).hasSize(2)

        // DB Unique 제약조건(uk_space_ranking_snapshot_period_space: period_type, period_start, space_id)을
        // 위반하는 동일 spaceId 중복 집계 데이터를 반환하도록 설정.
        // 이는 saveAll 진입 전 단순 doThrow가 아니라, saveAll에서 실제 DB INSERT(IDENTITY) 실행 시
        // MySQL InnoDB 엔진 레벨의 DataIntegrityViolationException(Duplicate entry)을 유발한다.
        val conflictingAggregates =
            listOf(
                SpaceRankingAggregate(101L, "서울 강남 신규", 80000L, 8, 16),
                SpaceRankingAggregate(101L, "서울 강남 중복키", 80000L, 8, 16),
            )
        doReturn(conflictingAggregates).`when`(aggregationRepository).aggregate(anyDateTime(), anyDateTime())

        // when & then: confirm 호출 시 deleteByPeriod가 먼저 실행된 후, DB saveAll 단계에서 실제 DB 제약조건 위반 예외 발생
        assertThatThrownBy {
            confirmService.confirm(testPeriodType, testDate)
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        // deleteByPeriod 메서드가 트랜잭션 내에서 실제로 호출되었음을 검증
        verify(snapshotRepository).deleteByPeriod(testPeriodType, periodStart)

        // then: 트랜잭션 롤백에 따라 독립 트랜잭션/세션에서 기존 행 2건의 id, computedAt 및 주요 비즈니스 값이 모두 온전히 보존되었는지 검증
        val preservedSnapshots = snapshotRepository.findByPeriodTypeAndPeriodStart(testPeriodType, periodStart)
        assertThat(preservedSnapshots).hasSize(2)

        val row101 = preservedSnapshots.first { it.spaceId == 101L }
        assertThat(row101.id).isEqualTo(savedInitial1.id)
        assertThat(row101.computedAt).isEqualTo(savedInitial1.computedAt)
        assertThat(row101.location).isEqualTo("서울 강남")
        assertThat(row101.netRevenue).isEqualTo(50000L)
        assertThat(row101.reservationCount).isEqualTo(5)
        assertThat(row101.slotCount).isEqualTo(10)

        val row102 = preservedSnapshots.first { it.spaceId == 102L }
        assertThat(row102.id).isEqualTo(savedInitial2.id)
        assertThat(row102.computedAt).isEqualTo(savedInitial2.computedAt)
        assertThat(row102.location).isEqualTo("서울 서초")
        assertThat(row102.netRevenue).isEqualTo(30000L)
        assertThat(row102.reservationCount).isEqualTo(3)
        assertThat(row102.slotCount).isEqualTo(6)

        // 부분적인 신규 행이 전혀 커밋되지 않았음을 확인
        assertThat(preservedSnapshots.none { it.location.contains("신규") }).isTrue()
        assertThat(preservedSnapshots.none { it.location.contains("중복키") }).isTrue()
    }

    @Test
    @DisplayName("대조군: 재확정 중 정상적인 새 집계가 반환되면 기존 행이 삭제되고 새 행으로 온전히 교체 커밋된다")
    fun confirm_whenSuccess_replacesOldRowsCleanly() {
        // given: 기존 행 1건 존재
        val initialRow =
            SpaceRankingSnapshot(
                testPeriodType,
                periodStart,
                101L,
                "서울 강남 과거",
                10000L,
                1,
                2,
                LocalDateTime.of(2026, 3, 11, 0, 1, 0),
            )
        snapshotRepository.save(initialRow)
        assertThat(snapshotRepository.findByPeriodTypeAndPeriodStart(testPeriodType, periodStart)).hasSize(1)

        // 새 정상 집계 반환
        val newAggregates =
            listOf(
                SpaceRankingAggregate(101L, "서울 강남 갱신", 70000L, 7, 14),
                SpaceRankingAggregate(103L, "서울 송파 신규", 40000L, 4, 8),
            )
        doReturn(newAggregates).`when`(aggregationRepository).aggregate(anyDateTime(), anyDateTime())

        // when: 정상 confirm 실행
        val savedCount = confirmService.confirm(testPeriodType, testDate)
        assertThat(savedCount).isEqualTo(2)

        // then: 기존 행 삭제 및 새 행 커밋 확인
        val currentSnapshots = snapshotRepository.findByPeriodTypeAndPeriodStart(testPeriodType, periodStart)
        assertThat(currentSnapshots).hasSize(2)

        val updated101 = currentSnapshots.first { it.spaceId == 101L }
        assertThat(updated101.location).isEqualTo("서울 강남 갱신")
        assertThat(updated101.netRevenue).isEqualTo(70000L)

        val updated103 = currentSnapshots.first { it.spaceId == 103L }
        assertThat(updated103.location).isEqualTo("서울 송파 신규")
        assertThat(updated103.netRevenue).isEqualTo(40000L)
    }
}
