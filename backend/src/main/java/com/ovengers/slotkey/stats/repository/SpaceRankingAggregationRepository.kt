package com.ovengers.slotkey.stats.repository

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDateTime

@Repository
class SpaceRankingAggregationRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun aggregate(
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<SpaceRankingAggregate> {
        val range = MapSqlParameterSource().addValue("from", from).addValue("to", to)
        val revenueBySpace = mutableMapOf<Long, Long>()
        jdbc.query(REVENUE_SQL, range) { rs: ResultSet ->
            revenueBySpace[rs.getLong("space_id")] = rs.getLong("net_revenue")
        }

        val countsBySpace = mutableMapOf<Long, IntArray>()
        jdbc.query(COUNT_SQL, range) { rs: ResultSet ->
            countsBySpace[rs.getLong("space_id")] =
                intArrayOf(rs.getInt("reservation_count"), rs.getInt("slot_count"))
        }

        val spaceIds = (revenueBySpace.keys + countsBySpace.keys).toSet()
        if (spaceIds.isEmpty()) return emptyList()

        val locationBySpace = mutableMapOf<Long, String>()
        jdbc.query(LOCATION_SQL, MapSqlParameterSource("ids", spaceIds)) { rs: ResultSet ->
            locationBySpace[rs.getLong("id")] = rs.getString("location")
        }

        return spaceIds
            .map { spaceId ->
                val counts = countsBySpace[spaceId] ?: intArrayOf(0, 0)
                SpaceRankingAggregate(
                    spaceId,
                    locationBySpace[spaceId],
                    revenueBySpace[spaceId] ?: 0L,
                    counts[0],
                    counts[1],
                )
            }.filterNot(SpaceRankingAggregate::isAllZero)
    }

    companion object {
        private const val REVENUE_SQL = """
            SELECT r.space_id AS space_id, -SUM(t.amount) AS net_revenue
            FROM credit_transaction t
            JOIN reservation r ON r.id = t.reservation_id
            WHERE t.type IN ('RESERVATION_CHARGE', 'REFUND', 'PENALTY')
              AND t.created_at >= :from AND t.created_at < :to
            GROUP BY r.space_id
        """

        private const val COUNT_SQL = """
            SELECT space_id,
                   COUNT(*) AS reservation_count,
                   SUM(TIMESTAMPDIFF(MINUTE, start_time, end_time) DIV 30) AS slot_count
            FROM reservation
            WHERE status IN ('CONFIRMED', 'IN_USE', 'COMPLETED', 'NO_SHOW')
              AND start_time >= :from AND start_time < :to
            GROUP BY space_id
        """

        private const val LOCATION_SQL = "SELECT id, location FROM spaces WHERE id IN (:ids)"
    }
}
