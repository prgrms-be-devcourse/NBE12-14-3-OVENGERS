package com.ovengers.slotkey.stats.entity

import com.ovengers.slotkey.stats.repository.SpaceRankingSnapshotRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.Hibernate
import org.hibernate.proxy.HibernateProxy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.time.LocalDateTime

@Transactional
class SpaceRankingSnapshotPersistenceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var snapshotRepository: SpaceRankingSnapshotRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @BeforeEach
    fun setUp() {
        snapshotRepository.deleteAllInBatch()
        entityManager.flush()
        entityManager.clear()
    }

    @Test
    @DisplayName("SpaceRankingSnapshot을 저장 후 flush/clear 뒤 재조회하면 Int 범위를 넘는 양수 및 음수 순매출을 포함한 모든 필드가 보존된다")
    fun saveFlushClearAndReload_preservesAllFieldsIncludingSignedLongBoundaries() {
        val periodStart = LocalDate.of(2026, 10, 19)
        val computedAt = LocalDateTime.of(2026, 10, 20, 0, 1, 0)
        val overflowRevenue = Int.MAX_VALUE.toLong() + 987_654_321L
        val negativeRevenue = -125_000L

        val positiveEntity =
            SpaceRankingSnapshot(
                RankingPeriodType.WEEKLY,
                periodStart,
                101L,
                "판교 테크노밸리",
                overflowRevenue,
                42,
                120,
                computedAt,
            )
        val negativeEntity =
            SpaceRankingSnapshot(
                RankingPeriodType.DAILY,
                periodStart,
                202L,
                "강남 역삼",
                negativeRevenue,
                0,
                0,
                computedAt,
            )

        val savedPositive = snapshotRepository.save(positiveEntity)
        val savedNegative = snapshotRepository.save(negativeEntity)
        entityManager.flush()
        val positiveId = requireNotNull(savedPositive.id)
        val negativeId = requireNotNull(savedNegative.id)
        entityManager.clear()

        val reloadedPositive = snapshotRepository.findById(positiveId).orElseThrow()
        assertThat(reloadedPositive.id).isEqualTo(positiveId)
        assertThat(reloadedPositive.periodType).isEqualTo(RankingPeriodType.WEEKLY)
        assertThat(reloadedPositive.periodStart).isEqualTo(periodStart)
        assertThat(reloadedPositive.spaceId).isEqualTo(101L)
        assertThat(reloadedPositive.getSpaceId()).isEqualTo(101L)
        assertThat(reloadedPositive.location).isEqualTo("판교 테크노밸리")
        assertThat(reloadedPositive.netRevenue).isEqualTo(overflowRevenue)
        assertThat(reloadedPositive.reservationCount).isEqualTo(42)
        assertThat(reloadedPositive.slotCount).isEqualTo(120)
        assertThat(reloadedPositive.computedAt).isEqualTo(computedAt)

        val reloadedNegative = snapshotRepository.findById(negativeId).orElseThrow()
        assertThat(reloadedNegative.id).isEqualTo(negativeId)
        assertThat(reloadedNegative.periodType).isEqualTo(RankingPeriodType.DAILY)
        assertThat(reloadedNegative.periodStart).isEqualTo(periodStart)
        assertThat(reloadedNegative.spaceId).isEqualTo(202L)
        assertThat(reloadedNegative.getSpaceId()).isEqualTo(202L)
        assertThat(reloadedNegative.location).isEqualTo("강남 역삼")
        assertThat(reloadedNegative.netRevenue).isEqualTo(negativeRevenue)
        assertThat(reloadedNegative.reservationCount).isZero()
        assertThat(reloadedNegative.slotCount).isZero()
        assertThat(reloadedNegative.computedAt).isEqualTo(computedAt)
    }

    @Test
    @DisplayName("Hibernate 지연 참조 프록시에서 최초 접근이 Kotlin proxy.spaceId여도 프록시가 초기화되고 모든 getter와 바이트코드 계약이 일치한다")
    fun hibernateLazyReferenceProxy_firstAccessViaKotlinSpaceIdInitializesProxyAndMatchesContract() {
        val periodStart = LocalDate.of(2026, 10, 1)
        val computedAt = LocalDateTime.of(2026, 11, 1, 0, 1, 0)
        val saved =
            snapshotRepository.save(
                SpaceRankingSnapshot(
                    RankingPeriodType.MONTHLY,
                    periodStart,
                    777L,
                    "성수 연무장길",
                    5_500_000L,
                    18,
                    54,
                    computedAt,
                ),
            )
        entityManager.flush()
        val savedId = requireNotNull(saved.id)
        entityManager.clear()

        val proxy = entityManager.getReference(SpaceRankingSnapshot::class.java, savedId)
        assertThat(proxy).isInstanceOf(HibernateProxy::class.java)
        assertThat(Hibernate.isInitialized(proxy)).isFalse()

        // 미초기화 프록시에 대한 최초 접근을 반드시 Kotlin 프로퍼티 proxy.spaceId로 수행한다.
        val firstReadSpaceId = proxy.spaceId
        assertThat(firstReadSpaceId).isEqualTo(777L)
        assertThat(Hibernate.isInitialized(proxy)).isTrue()

        val primitiveSpaceId = SpaceRankingSnapshot::class.java.getDeclaredMethod("getSpaceIdValue").invoke(proxy)
        assertThat(primitiveSpaceId).isEqualTo(777L)
        assertThat(proxy.getSpaceId()).isEqualTo(777L)
        assertThat(proxy.id).isEqualTo(savedId)
        assertThat(proxy.periodType).isEqualTo(RankingPeriodType.MONTHLY)
        assertThat(proxy.periodStart).isEqualTo(periodStart)
        assertThat(proxy.location).isEqualTo("성수 연무장길")
        assertThat(proxy.netRevenue).isEqualTo(5_500_000L)
        assertThat(proxy.reservationCount).isEqualTo(18)
        assertThat(proxy.slotCount).isEqualTo(54)
        assertThat(proxy.computedAt).isEqualTo(computedAt)

        val clazz = SpaceRankingSnapshot::class.java
        assertThat(Modifier.isFinal(clazz.modifiers)).isFalse()

        val noArgCtor = clazz.getDeclaredConstructor()
        assertThat(Modifier.isProtected(noArgCtor.modifiers)).isTrue()

        val eightArgCtor =
            clazz.getDeclaredConstructor(
                RankingPeriodType::class.java,
                LocalDate::class.java,
                java.lang.Long::class.java,
                String::class.java,
                java.lang.Long.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                LocalDateTime::class.java,
            )
        assertThat(Modifier.isPublic(eightArgCtor.modifiers)).isTrue()

        val expectedGetters =
            mapOf(
                "getId" to java.lang.Long::class.java,
                "getPeriodType" to RankingPeriodType::class.java,
                "getPeriodStart" to LocalDate::class.java,
                "getSpaceIdValue" to java.lang.Long.TYPE,
                "getSpaceId" to java.lang.Long::class.java,
                "getLocation" to String::class.java,
                "getNetRevenue" to java.lang.Long.TYPE,
                "getReservationCount" to Integer.TYPE,
                "getSlotCount" to Integer.TYPE,
                "getComputedAt" to LocalDateTime::class.java,
            )
        for ((getterName, returnType) in expectedGetters) {
            val method = clazz.getDeclaredMethod(getterName)
            assertThat(Modifier.isPublic(method.modifiers)).`as`(getterName).isTrue()
            assertThat(Modifier.isFinal(method.modifiers)).`as`(getterName).isFalse()
            assertThat(method.returnType).`as`(getterName).isEqualTo(returnType)
        }
    }

    @Test
    @DisplayName("생성자에서 null spaceId는 거부되며 동일 속성값을 가진 서로 다른 엔티티 인스턴스는 값 동등성이 아닌 참조 동등성을 유지한다")
    fun constructorRejectsNullSpaceId_andEntityPreservesIdentityEqualitySemantics() {
        val periodStart = LocalDate.of(2026, 10, 15)
        val computedAt = LocalDateTime.of(2026, 10, 16, 0, 1, 0)

        assertThatThrownBy {
            SpaceRankingSnapshot(
                RankingPeriodType.DAILY,
                periodStart,
                null,
                "판교",
                10_000L,
                1,
                2,
                computedAt,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        val first =
            SpaceRankingSnapshot(
                RankingPeriodType.DAILY,
                periodStart,
                300L,
                "판교",
                10_000L,
                1,
                2,
                computedAt,
            )
        val second =
            SpaceRankingSnapshot(
                RankingPeriodType.DAILY,
                periodStart,
                300L,
                "판교",
                10_000L,
                1,
                2,
                computedAt,
            )

        assertThat(first).isEqualTo(first)
        assertThat(first.hashCode()).isEqualTo(System.identityHashCode(first))
        assertThat(first).isNotEqualTo(second)

        val savedFirst = snapshotRepository.save(first)
        val savedSecond =
            snapshotRepository.save(
                SpaceRankingSnapshot(
                    RankingPeriodType.WEEKLY,
                    periodStart,
                    300L,
                    "판교",
                    10_000L,
                    1,
                    2,
                    computedAt,
                ),
            )
        entityManager.flush()

        assertThat(savedFirst.id).isNotNull()
        assertThat(savedSecond.id).isNotNull()
        assertThat(savedFirst).isNotEqualTo(savedSecond)
    }
}
