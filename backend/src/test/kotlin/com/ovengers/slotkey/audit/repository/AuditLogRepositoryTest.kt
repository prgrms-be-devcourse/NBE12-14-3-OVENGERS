package com.ovengers.slotkey.audit.repository

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.support.FixedClockConfig
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Optional

@Import(FixedClockConfig::class)
class AuditLogRepositoryTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setUp() {
        auditLogRepository.deleteAllInBatch()
    }

    private fun insertAuditLog(
        actorMemberId: Long?,
        action: AuditAction,
        targetType: AuditTargetType,
        targetId: Long,
        reason: String?,
        beforeValue: String?,
        afterValue: String?,
        createdAt: LocalDateTime,
    ): Long {
        val sql =
            """
            INSERT INTO audit_logs (actor_member_id, action, target_type, target_id, reason, before_value, after_value, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        jdbcTemplate.update(
            sql,
            actorMemberId,
            action.name,
            targetType.name,
            targetId,
            reason,
            beforeValue,
            afterValue,
            Timestamp.valueOf(createdAt),
        )

        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
    }

    @Test
    @DisplayName("필터 없이 전체 조회 시 모든 로그가 createdAt DESC, id DESC 순으로 조회된다")
    fun searchAuditLogs_noFilter_returnsAllInOrder() {
        // given
        val t1 = LocalDateTime.of(2026, 9, 20, 10, 0, 0)
        val t2 = LocalDateTime.of(2026, 9, 20, 11, 0, 0)

        val id1 = insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 10L, "등록", null, null, t1)
        val id2 = insertAuditLog(1L, AuditAction.MODIFY_SPACE, AuditTargetType.SPACE, 10L, "수정", null, null, t2)

        val pageable: Pageable = PageRequest.of(0, 10, DEFAULT_SORT)

        // when
        val result: Page<AuditLog> =
            auditLogRepository.searchAuditLogs(
                null,
                null,
                null,
                null,
                null,
                null,
                pageable,
            )

        // then
        assertThat(result.totalElements).isEqualTo(2)
        assertThat(result.content.map { it.id }).containsExactly(id2, id1)
    }

    @Test
    @DisplayName("actorMemberId, action, targetType, targetId 개별 필터 및 복합 AND 조건 검색이 정상 동작한다")
    fun searchAuditLogs_filteringAndCompositeAnd() {
        // given
        val now = LocalDateTime.of(2026, 9, 20, 12, 0, 0)

        val matchId = insertAuditLog(1L, AuditAction.MODIFY_SPACE, AuditTargetType.SPACE, 10L, "일치", null, null, now)
        insertAuditLog(2L, AuditAction.MODIFY_SPACE, AuditTargetType.SPACE, 10L, "actor 불일치", null, null, now)
        insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 10L, "action 불일치", null, null, now)
        insertAuditLog(1L, AuditAction.MODIFY_SPACE, AuditTargetType.RESERVATION, 10L, "targetType 불일치", null, null, now)
        insertAuditLog(1L, AuditAction.MODIFY_SPACE, AuditTargetType.SPACE, 20L, "targetId 불일치", null, null, now)

        val pageable: Pageable = PageRequest.of(0, 10, DEFAULT_SORT)

        // 1. actorMemberId 개별 필터
        val byActor: Page<AuditLog> =
            auditLogRepository.searchAuditLogs(
                1L,
                null,
                null,
                null,
                null,
                null,
                pageable,
            )
        assertThat(byActor.totalElements).isEqualTo(4)

        // 2. action 개별 필터
        val byAction: Page<AuditLog> =
            auditLogRepository.searchAuditLogs(
                null,
                AuditAction.REGISTER_SPACE,
                null,
                null,
                null,
                null,
                pageable,
            )
        assertThat(byAction.totalElements).isEqualTo(1)

        // 3. targetType 개별 필터
        val byTargetType: Page<AuditLog> =
            auditLogRepository.searchAuditLogs(
                null,
                null,
                AuditTargetType.RESERVATION,
                null,
                null,
                null,
                pageable,
            )
        assertThat(byTargetType.totalElements).isEqualTo(1)

        // 4. targetId 개별 필터
        val byTargetId: Page<AuditLog> =
            auditLogRepository.searchAuditLogs(
                null,
                null,
                null,
                20L,
                null,
                null,
                pageable,
            )
        assertThat(byTargetId.totalElements).isEqualTo(1)

        // 5. 4가지 조건 복합 AND 검색
        val composite: Page<AuditLog> =
            auditLogRepository.searchAuditLogs(
                1L,
                AuditAction.MODIFY_SPACE,
                AuditTargetType.SPACE,
                10L,
                null,
                null,
                pageable,
            )
        assertThat(composite.totalElements).isEqualTo(1)
        assertThat(composite.content[0].id).isEqualTo(matchId)
    }

    @Test
    @DisplayName("날짜 반개구간 [fromInclusive, toExclusive) 검색 시 from 00:00은 포함하고 to 00:00은 제외한다")
    fun searchAuditLogs_halfOpenDateIntervalBoundary() {
        // given
        val beforeBoundary = LocalDateTime.of(2026, 9, 19, 23, 59, 59)
        val fromBoundary = LocalDateTime.of(2026, 9, 20, 0, 0, 0)
        val middleOfDay = LocalDateTime.of(2026, 9, 20, 12, 0, 0)
        val endOfDay = LocalDateTime.of(2026, 9, 20, 23, 59, 59)
        val toBoundary = LocalDateTime.of(2026, 9, 21, 0, 0, 0)
        val afterBoundary = LocalDateTime.of(2026, 9, 21, 0, 0, 1)

        insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 1L, "before", null, null, beforeBoundary)
        val idFrom = insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 2L, "from", null, null, fromBoundary)
        val idMid = insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 3L, "mid", null, null, middleOfDay)
        val idEnd = insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 4L, "end", null, null, endOfDay)
        insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 5L, "to", null, null, toBoundary)
        insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 6L, "after", null, null, afterBoundary)

        val pageable: Pageable = PageRequest.of(0, 10, DEFAULT_SORT)

        // when: [2026-09-20 00:00:00, 2026-09-21 00:00:00)
        val fromInclusive = LocalDateTime.of(2026, 9, 20, 0, 0, 0)
        val toExclusive = LocalDateTime.of(2026, 9, 21, 0, 0, 0)

        val result: Page<AuditLog> =
            auditLogRepository.searchAuditLogs(
                null,
                null,
                null,
                null,
                fromInclusive,
                toExclusive,
                pageable,
            )

        // then: fromBoundary(포함), middleOfDay(포함), endOfDay(포함) 총 3건 / before, to, after는 제외
        assertThat(result.totalElements).isEqualTo(3)
        assertThat(result.content.map { it.id })
            .containsExactly(idEnd, idMid, idFrom)
    }

    @Test
    @DisplayName("동일한 createdAt을 가진 다수의 로그가 2페이지 이상으로 페이징될 때 id DESC 순서가 유지되고 중복 및 누락이 없다")
    fun searchAuditLogs_sameCreatedAt_paginatesByIdDescWithoutDuplicateOrOmission() {
        // given: FixedClockConfig의 동일 시각으로 5개 저장
        val fixedTime = FixedClockConfig.FIXED_DATE_TIME

        val id1 = insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 1L, "1", null, null, fixedTime)
        val id2 = insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 2L, "2", null, null, fixedTime)
        val id3 = insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 3L, "3", null, null, fixedTime)
        val id4 = insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 4L, "4", null, null, fixedTime)
        val id5 = insertAuditLog(1L, AuditAction.REGISTER_SPACE, AuditTargetType.SPACE, 5L, "5", null, null, fixedTime)

        // when: size=2로 0, 1, 2 페이지 순차 조회
        val page0: Pageable = PageRequest.of(0, 2, DEFAULT_SORT)
        val page1: Pageable = PageRequest.of(1, 2, DEFAULT_SORT)
        val page2: Pageable = PageRequest.of(2, 2, DEFAULT_SORT)

        val res0 = auditLogRepository.searchAuditLogs(null, null, null, null, null, null, page0)
        val res1 = auditLogRepository.searchAuditLogs(null, null, null, null, null, null, page1)
        val res2 = auditLogRepository.searchAuditLogs(null, null, null, null, null, null, page2)

        // then
        assertThat(res0.totalElements).isEqualTo(5)
        assertThat(res0.content.map { it.id }).containsExactly(id5, id4)
        assertThat(res1.content.map { it.id }).containsExactly(id3, id2)
        assertThat(res2.content.map { it.id }).containsExactly(id1)
    }

    @Test
    @DisplayName("최근 회원 상태 단건 조회 시 동일한 createdAt이라도 id DESC 우선순위로 최신 행을 결정적으로 반환한다")
    fun findFirstByTargetTypeAndTargetIdAndActionInOrderByCreatedAtDescIdDesc_sameCreatedAt_returnsHigherId() {
        // given: 동일한 createdAt을 가진 SUSPEND_MEMBER와 REACTIVATE_MEMBER를 순서대로 저장
        val sameTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)

        val firstId =
            insertAuditLog(
                1L,
                AuditAction.SUSPEND_MEMBER,
                AuditTargetType.MEMBER,
                100L,
                "1차 정지 사유",
                null,
                null,
                sameTime,
            )
        val secondId =
            insertAuditLog(
                1L,
                AuditAction.REACTIVATE_MEMBER,
                AuditTargetType.MEMBER,
                100L,
                "소명 접수로 복구",
                null,
                null,
                sameTime,
            )

        assertThat(secondId).isGreaterThan(firstId)

        // when
        val found: Optional<AuditLog> =
            auditLogRepository.findFirstByTargetTypeAndTargetIdAndActionInOrderByCreatedAtDescIdDesc(
                AuditTargetType.MEMBER,
                100L,
                listOf(AuditAction.SUSPEND_MEMBER, AuditAction.REACTIVATE_MEMBER),
            )

        // then: 동일한 시각이더라도 id가 더 큰 두 번째 행(복구)이 반환되어야 함
        assertThat(found).isPresent()
        assertThat(found.get().id).isEqualTo(secondId)
        assertThat(found.get().action).isEqualTo(AuditAction.REACTIVATE_MEMBER)
        assertThat(found.get().reason).isEqualTo("소명 접수로 복구")
    }

    @Test
    @DisplayName("서로 다른 시각의 회원 상태 감사 로그 중 가장 최근 시각의 로그를 반환한다")
    fun findFirstByTargetTypeAndTargetIdAndActionInOrderByCreatedAtDescIdDesc_differentCreatedAt_returnsLatest() {
        // given
        val t1 = LocalDateTime.of(2026, 9, 20, 10, 0, 0)
        val t2 = LocalDateTime.of(2026, 9, 20, 11, 0, 0)

        insertAuditLog(1L, AuditAction.SUSPEND_MEMBER, AuditTargetType.MEMBER, 100L, "1차 정지", null, null, t1)
        val latestId =
            insertAuditLog(
                1L,
                AuditAction.REACTIVATE_MEMBER,
                AuditTargetType.MEMBER,
                100L,
                "복구",
                null,
                null,
                t2,
            )

        // when
        val found: Optional<AuditLog> =
            auditLogRepository.findFirstByTargetTypeAndTargetIdAndActionInOrderByCreatedAtDescIdDesc(
                AuditTargetType.MEMBER,
                100L,
                listOf(AuditAction.SUSPEND_MEMBER, AuditAction.REACTIVATE_MEMBER),
            )

        // then
        assertThat(found).isPresent()
        assertThat(found.get().id).isEqualTo(latestId)
        assertThat(found.get().action).isEqualTo(AuditAction.REACTIVATE_MEMBER)
    }

    @Test
    @DisplayName("회원 정지 기록은 관리자 정지와 시스템 잠금만 집계한다")
    fun countSuspensionLogs_onlySuspendAndLock() {
        // given
        val now = FixedClockConfig.FIXED_DATE_TIME

        // 회원 100번: 관리자 정지
        insertAuditLog(
            1L,
            AuditAction.SUSPEND_MEMBER,
            AuditTargetType.MEMBER,
            100L,
            "관리자 정지",
            null,
            null,
            now,
        )

        // 회원 100번: 시스템 잠금
        insertAuditLog(
            null,
            AuditAction.LOCK_MEMBER_BY_VERIFICATION,
            AuditTargetType.MEMBER,
            100L,
            "인증 실패",
            null,
            null,
            now,
        )

        // 회원 100번: 복구 (집계에서 제외)
        insertAuditLog(
            1L,
            AuditAction.REACTIVATE_MEMBER,
            AuditTargetType.MEMBER,
            100L,
            "복구",
            null,
            null,
            now,
        )

        // 회원 200번: 다른 회원이므로 제외
        insertAuditLog(
            1L,
            AuditAction.SUSPEND_MEMBER,
            AuditTargetType.MEMBER,
            200L,
            "다른 회원 정지",
            null,
            null,
            now,
        )

        // when
        val count =
            auditLogRepository.countByTargetTypeAndTargetIdAndActionIn(
                AuditTargetType.MEMBER,
                100L,
                listOf(
                    AuditAction.SUSPEND_MEMBER,
                    AuditAction.LOCK_MEMBER_BY_VERIFICATION,
                ),
            )

        // then
        assertThat(count).isEqualTo(2L)
    }

    companion object {
        private val DEFAULT_SORT: Sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
    }
}
