package com.ovengers.slotkey.stats.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SpaceRankingAggregateTest {
    @Test
    @DisplayName("SpaceRankingAggregate는 Java Record 바이너리 계약(컴포넌트 이름·순서·타입·접근자·정준 생성자)을 유지한다")
    fun jvmRecordBinaryContract_preservesRecordComponentsAccessorsAndCanonicalConstructor() {
        val clazz = SpaceRankingAggregate::class.java
        assertThat(clazz.isRecord).isTrue()
        assertThat(clazz.superclass).isEqualTo(java.lang.Record::class.java)

        val components = clazz.recordComponents
        assertThat(components.map { it.name }).containsExactly(
            "spaceId",
            "location",
            "netRevenue",
            "reservationCount",
            "slotCount",
        )
        assertThat(components.map { it.type }).containsExactly(
            java.lang.Long.TYPE,
            String::class.java,
            java.lang.Long.TYPE,
            Integer.TYPE,
            Integer.TYPE,
        )

        val canonicalCtor =
            clazz.getDeclaredConstructor(
                java.lang.Long.TYPE,
                String::class.java,
                java.lang.Long.TYPE,
                Integer.TYPE,
                Integer.TYPE,
            )
        val instance = canonicalCtor.newInstance(99L, "판교", 250_000L, 5, 12)

        assertThat(clazz.getDeclaredMethod("spaceId").invoke(instance)).isEqualTo(99L)
        assertThat(clazz.getDeclaredMethod("location").invoke(instance)).isEqualTo("판교")
        assertThat(clazz.getDeclaredMethod("netRevenue").invoke(instance)).isEqualTo(250_000L)
        assertThat(clazz.getDeclaredMethod("reservationCount").invoke(instance)).isEqualTo(5)
        assertThat(clazz.getDeclaredMethod("slotCount").invoke(instance)).isEqualTo(12)
    }

    @Test
    @DisplayName("nullable location과 부호 있는 long 경계값(음수 및 Int.MAX_VALUE 초과)에서 동등성·해시코드가 일관되게 동작한다")
    fun equalityHashCodeAndBoundaries_supportNullableLocationAndSignedLongValues() {
        val overflowRevenue = Int.MAX_VALUE.toLong() + 500_000_000L
        val first = SpaceRankingAggregate(15L, null, overflowRevenue, 3, 9)
        val identical = SpaceRankingAggregate(15L, null, overflowRevenue, 3, 9)
        val negativeRevenue = SpaceRankingAggregate(15L, "강남", -50_000L, 0, 0)

        assertThat(first.location).isNull()
        assertThat(first.netRevenue).isEqualTo(overflowRevenue)
        assertThat(first).isEqualTo(identical)
        assertThat(first.hashCode()).isEqualTo(identical.hashCode())
        assertThat(first).isNotEqualTo(negativeRevenue)
        assertThat(negativeRevenue.netRevenue).isEqualTo(-50_000L)
    }

    @Test
    @DisplayName("isAllZero는 spaceId·location과 무관하게 세 실적 지표(netRevenue, reservationCount, slotCount)가 모두 0일 때만 true를 반환한다")
    fun isAllZero_returnsTrueOnlyWhenAllThreeMetricsAreZero() {
        assertThat(SpaceRankingAggregate(1L, "판교", 0L, 0, 0).isAllZero()).isTrue()
        assertThat(SpaceRankingAggregate(999L, null, 0L, 0, 0).isAllZero()).isTrue()

        assertThat(SpaceRankingAggregate(1L, "판교", 1L, 0, 0).isAllZero()).isFalse()
        assertThat(SpaceRankingAggregate(1L, "판교", -1L, 0, 0).isAllZero()).isFalse()
        assertThat(SpaceRankingAggregate(1L, "판교", 0L, 1, 0).isAllZero()).isFalse()
        assertThat(SpaceRankingAggregate(1L, "판교", 0L, 0, 1).isAllZero()).isFalse()
    }
}
