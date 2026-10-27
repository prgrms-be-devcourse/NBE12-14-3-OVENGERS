package com.ovengers.slotkey.notification

import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.LocalDateTime

@AutoConfigureMockMvc
class NotificationReadIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var memberRepository: MemberRepository

    @Test
    fun readsOnlyMyUnreadAndKeepsExistingReadTime() {
        val me = createMember(0)
        val other = createMember(0)
        insert(me, "mine-1", null)
        insert(me, "mine-2", null)
        val original = LocalDateTime.of(2026, 10, 1, 12, 0)
        insert(me, "read", Timestamp.valueOf(original))
        insert(other, "other", null)
        val upToId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM notification", Long::class.javaObjectType)!!
        insert(me, "newer", null)
        val token =
            issueSessionBoundAccessToken(
                memberRepository.findById(me).orElseThrow(),
                jwtProvider,
            )

        mvc
            .perform(post("/api/v1/notifications/read-all").param("upToId", upToId.toString()).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.updatedCount").value(2))
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE member_id=? AND read_at IS NULL",
                Int::class.javaObjectType,
                me,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT read_at FROM notification WHERE member_id=? AND event_id='newer'",
                Timestamp::class.java,
                me,
            ),
        ).isNull()
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT read_at FROM notification WHERE event_id='read' AND member_id=?",
                Timestamp::class.java,
                me,
            ),
        ).isEqualTo(Timestamp.valueOf(original))
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE member_id=? AND read_at IS NULL",
                Int::class.javaObjectType,
                other,
            ),
        ).isEqualTo(1)
        mvc
            .perform(post("/api/v1/notifications/read-all").param("upToId", upToId.toString()).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.updatedCount").value(0))
    }

    @Test
    fun noTokenReturns401AndLeavesUnread() {
        val me = createMember(0)
        insert(me, "unauth", null)
        mvc.perform(post("/api/v1/notifications/read-all").param("upToId", "1")).andExpect(status().isUnauthorized())
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE member_id=? AND read_at IS NULL",
                Int::class.javaObjectType,
                me,
            ),
        ).isEqualTo(1)
    }

    private fun insert(
        memberId: Long,
        eventId: String,
        readAt: Timestamp?,
    ) {
        jdbcTemplate.update(
            "INSERT INTO notification(member_id,waitlist_entry_id,event_id,message,created_at,read_at) VALUES (?,1,?,'message',NOW(),?)",
            memberId,
            eventId,
            readAt,
        )
    }
}
