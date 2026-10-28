package com.ovengers.slotkey.stats

import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** V22 마이그레이션이 만든 스키마(테이블·유니크·집계 인덱스)를 information_schema로 확인한다. */
class SpaceRankingSchemaIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("space_ranking_snapshot 테이블은 설계한 컬럼을 모두 가진다")
    fun snapshotTableHasColumns() {
        val columns =
            jdbcTemplate.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'space_ranking_snapshot'
                ORDER BY ordinal_position
                """.trimIndent(),
                String::class.java,
            )

        assertThat(columns).containsExactly(
            "id",
            "period_type",
            "period_start",
            "space_id",
            "location",
            "net_revenue",
            "reservation_count",
            "slot_count",
            "computed_at",
        )
    }

    @Test
    @DisplayName("(period_type, period_start, space_id)는 UNIQUE이다")
    fun snapshotUniqueKey() {
        assertThat(indexColumns("space_ranking_snapshot", "uk_space_ranking_snapshot_period_space"))
            .containsExactly("period_type", "period_start", "space_id")
        assertThat(isUnique("space_ranking_snapshot", "uk_space_ranking_snapshot_period_space")).isTrue()
    }

    @Test
    @DisplayName("space_ranking_snapshot에는 FK가 없다")
    fun snapshotHasNoForeignKey() {
        val count =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = DATABASE() AND table_name = 'space_ranking_snapshot'
                  AND constraint_type = 'FOREIGN KEY'
                """.trimIndent(),
                Int::class.javaObjectType,
            )!!
        assertThat(count).isZero()
    }

    @Test
    @DisplayName("순매출은 음수가 될 수 있도록 부호 있는 BIGINT이다")
    fun netRevenueIsSignedBigint() {
        val type =
            jdbcTemplate.queryForObject(
                """
                SELECT column_type FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'space_ranking_snapshot'
                  AND column_name = 'net_revenue'
                """.trimIndent(),
                String::class.java,
            )
        assertThat(type).isEqualToIgnoringCase("bigint")
    }

    @Test
    @DisplayName("reservation(status, start_time, space_id) 집계 인덱스가 있다")
    fun reservationAggregationIndex() {
        assertThat(indexColumns("reservation", "idx_reservation_status_start_space"))
            .containsExactly("status", "start_time", "space_id")
    }

    @Test
    @DisplayName("credit_transaction(type, created_at, reservation_id) 집계 인덱스가 있다")
    fun creditTransactionAggregationIndex() {
        assertThat(indexColumns("credit_transaction", "idx_credit_transaction_type_created_reservation"))
            .containsExactly("type", "created_at", "reservation_id")
    }

    private fun indexColumns(
        table: String,
        index: String,
    ): List<String> =
        jdbcTemplate.queryForList(
            """
            SELECT column_name FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
            ORDER BY seq_in_index
            """.trimIndent(),
            String::class.java,
            table,
            index,
        )

    private fun isUnique(
        table: String,
        index: String,
    ): Boolean {
        val nonUnique =
            jdbcTemplate.queryForObject(
                """
                SELECT MIN(non_unique) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """.trimIndent(),
                Int::class.javaObjectType,
                table,
                index,
            )
        return nonUnique == 0
    }
}
