package com.ovengers.slotkey.stats.repository

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.EnumMap

@Repository
class ReservationStatusCountRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun countByStatus(
        from: LocalDateTime,
        to: LocalDateTime,
        spaceId: Long?,
    ): Map<ReservationStatus, Long> {
        val counts = EnumMap<ReservationStatus, Long>(ReservationStatus::class.java)
        val params =
            MapSqlParameterSource()
                .addValue("from", from)
                .addValue("to", to)
                .addValue("spaceId", spaceId)
        jdbc.query(COUNT_SQL, params) { rs: ResultSet ->
            counts[ReservationStatus.valueOf(rs.getString("status"))] = rs.getLong("cnt")
        }
        return counts
    }

    companion object {
        private const val COUNT_SQL = """
            SELECT status, COUNT(*) AS cnt
            FROM reservation
            WHERE start_time >= :from AND start_time < :to
              AND (:spaceId IS NULL OR space_id = :spaceId)
            GROUP BY status
        """
    }
}
