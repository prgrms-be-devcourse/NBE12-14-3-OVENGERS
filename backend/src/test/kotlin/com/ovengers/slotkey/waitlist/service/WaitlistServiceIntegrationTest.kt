package com.ovengers.slotkey.waitlist.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.waitlist.dto.response.WaitlistEntryResponse
import com.ovengers.slotkey.waitlist.entity.WaitlistStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import java.time.LocalDateTime

/**
 * 대기 신청(1단계, docs/plans/waitlist-outbox/01-waitlist-rules.md)을 실제 MySQL로 검증한다.
 * 점유 여부 판정은 기존 예약 슬롯(reservation_slot + reservation.status)을 그대로 읽고,
 * 중복 방지는 waitlist_entry의 UNIQUE 제약이 담당한다는 점이 검증 대상이다.
 *
 * 기본 상황: 공간 한 곳의 내일 14:00~14:30 슬롯을 타인(occupier)이 CONFIRMED로 점유하고 있다.
 */
class WaitlistServiceIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var waitlistService: WaitlistService

    private var memberId: Long = 0L
    private var occupierId: Long = 0L
    private var spaceId: Long = 0L
    private lateinit var startTime: LocalDateTime
    private lateinit var endTime: LocalDateTime

    @BeforeEach
    fun setUp() {
        memberId = createMember(BALANCE)
        occupierId = createMember(BALANCE)
        spaceId = createSpace()
        startTime = tomorrowAt(14, 0)
        endTime = tomorrowAt(15, 0)
        // 신청 구간(14:00~15:00) 중 앞쪽 슬롯 한 개만 타인이 점유 → 대기 신청 대상
        createReservation(occupierId, spaceId, ReservationStatus.CONFIRMED, startTime, startTime.plusMinutes(30))
    }

    // ---------- 신청 ----------

    @Test
    @DisplayName("구간 슬롯이 일부라도 점유 중이면 대기 신청이 WAITING으로 저장되고 신청 순서는 1이다")
    fun apply_partiallyOccupied_savesWaitingEntry() {
        val response = waitlistService.apply(memberId, spaceId, startTime, endTime)

        assertThat(response.id).isNotNull()
        assertThat(response.spaceId).isEqualTo(spaceId)
        assertThat(response.startTime).isEqualTo(startTime)
        assertThat(response.endTime).isEqualTo(endTime)
        assertThat(response.status).isEqualTo(WaitlistStatus.WAITING)
        assertThat(response.position).isEqualTo(1)
        assertThat(statusOfWaitlistEntry(response.id)).isEqualTo("WAITING")
    }

    @Test
    @DisplayName("구간 슬롯이 하나도 점유되지 않았으면 WAITLIST_NOT_NEEDED로 거절한다")
    fun apply_nothingOccupied_rejected() {
        val freeStart = tomorrowAt(18, 0)
        val freeEnd = tomorrowAt(19, 0)

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, freeStart, freeEnd) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITLIST_NOT_NEEDED)
    }

    @Test
    @DisplayName("점유가 만료된 HELD뿐이면 점유로 보지 않아 WAITLIST_NOT_NEEDED로 거절한다")
    fun apply_onlyExpiredHoldOccupies_rejected() {
        val otherSpaceId = createSpace()
        val start = tomorrowAt(16, 0)
        val end = tomorrowAt(16, 30)
        val expiredHoldId = createReservation(occupierId, otherSpaceId, ReservationStatus.HELD, start, end)
        jdbcTemplate.update(
            "UPDATE reservation SET hold_expires_at = ? WHERE id = ?",
            now().minusMinutes(1),
            expiredHoldId,
        )

        assertThatThrownBy { waitlistService.apply(memberId, otherSpaceId, start, end) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITLIST_NOT_NEEDED)
    }

    @Test
    @DisplayName("30분 단위가 아닌 구간은 기존 예약 시간 정책대로 INVALID_RESERVATION_TIME으로 거절한다")
    fun apply_notSlotAligned_rejected() {
        val misaligned = startTime.plusMinutes(10)

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, misaligned, endTime) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("이미 지난 구간은 기존 예약 시간 정책대로 INVALID_RESERVATION_TIME으로 거절한다")
    fun apply_pastRange_rejected() {
        val pastStart =
            now()
                .minusDays(1)
                .withHour(14)
                .withMinute(0)
                .withSecond(0)

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, pastStart, pastStart.plusMinutes(30)) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("같은 공간에서 시간이 겹치는 본인 CONFIRMED 예약이 있으면 WAITLIST_ALREADY_HOLDING으로 거절한다")
    fun apply_ownConfirmedOverlaps_rejected() {
        createReservation(
            memberId,
            spaceId,
            ReservationStatus.CONFIRMED,
            startTime.plusMinutes(30),
            endTime,
        )

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, startTime, endTime) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITLIST_ALREADY_HOLDING)
    }

    @Test
    @DisplayName("같은 공간에서 시간이 겹치는 본인 HELD 예약이 아직 만료되지 않았으면 WAITLIST_ALREADY_HOLDING으로 거절한다")
    fun apply_ownLiveHeldOverlaps_rejected() {
        createReservation(
            memberId,
            spaceId,
            ReservationStatus.HELD,
            startTime.plusMinutes(30),
            endTime,
        )

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, startTime, endTime) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITLIST_ALREADY_HOLDING)
    }

    @Test
    @DisplayName("본인 HELD가 이미 만료됐으면 점유로 보지 않아(예약 경로와 같은 기준) 대기 신청이 가능하다")
    fun apply_ownExpiredHeldOverlaps_succeeds() {
        val expiredHoldId =
            createReservation(
                memberId,
                spaceId,
                ReservationStatus.HELD,
                startTime.plusMinutes(30),
                endTime,
            )
        jdbcTemplate.update(
            "UPDATE reservation SET hold_expires_at = ? WHERE id = ?",
            now().minusMinutes(1),
            expiredHoldId,
        )

        // 구간 앞쪽 슬롯은 타인(occupier)의 CONFIRMED가 점유 중이므로 WAITLIST_NOT_NEEDED가 아니다
        val response = waitlistService.apply(memberId, spaceId, startTime, endTime)

        assertThat(response.status).isEqualTo(WaitlistStatus.WAITING)
    }

    @Test
    @DisplayName("본인 COMPLETED 예약이 겹쳐도 신청할 수 있고, 그 슬롯 자체는 점유로 판정된다")
    fun apply_ownCompletedOverlaps_succeedsAndSlotStaysOccupied() {
        // 체크아웃은 슬롯을 지우지 않으므로(core-domain-decisions 3-4) COMPLETED의 슬롯은 점유로 남는다.
        // 반면 본인 점유 차단은 "자기 자리를 기다리는" 경우만 막으므로 COMPLETED는 대상이 아니다.
        // 조기 체크아웃이면 종료 시각이 아직 미래인 COMPLETED가 생길 수 있다(여기서는 그 상태를 고정으로 만든다).
        val emptySpaceId = createSpace()
        val completedId = createReservation(memberId, emptySpaceId, ReservationStatus.CONFIRMED, startTime, endTime)
        jdbcTemplate.update(
            "UPDATE reservation SET status = 'COMPLETED', checked_in_at = ?, checked_out_at = ?" +
                " WHERE id = ?",
            now(),
            now(),
            completedId,
        )

        // 이 공간에는 타인 점유가 없다 — 본인 COMPLETED 슬롯이 점유로 잡히므로 WAITLIST_NOT_NEEDED가 아니다
        val response = waitlistService.apply(memberId, emptySpaceId, startTime, endTime)

        assertThat(response.status).isEqualTo(WaitlistStatus.WAITING)
        assertThat(countSlotsInRange(emptySpaceId, startTime, endTime)).isEqualTo(2)
    }

    @Test
    @DisplayName("같은 공간이지만 시간이 겹치지 않는 본인 예약은 대기 신청을 막지 않는다")
    fun apply_ownReservationNotOverlapping_succeeds() {
        createReservation(
            memberId,
            spaceId,
            ReservationStatus.CONFIRMED,
            tomorrowAt(10, 0),
            tomorrowAt(11, 0),
        )

        val response = waitlistService.apply(memberId, spaceId, startTime, endTime)

        assertThat(response.status).isEqualTo(WaitlistStatus.WAITING)
    }

    @Test
    @DisplayName("다른 공간의 본인 예약은 시간이 겹쳐도 대기 신청을 막지 않는다")
    fun apply_ownReservationInOtherSpace_succeeds() {
        val otherSpaceId = createSpace()
        createReservation(memberId, otherSpaceId, ReservationStatus.CONFIRMED, startTime, endTime)

        val response = waitlistService.apply(memberId, spaceId, startTime, endTime)

        assertThat(response.status).isEqualTo(WaitlistStatus.WAITING)
    }

    @Test
    @DisplayName("활성 상태로 같은 공간·같은 구간을 다시 신청하면 WAITLIST_DUPLICATE로 거절한다")
    fun apply_duplicateWhileActive_rejected() {
        waitlistService.apply(memberId, spaceId, startTime, endTime)

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, startTime, endTime) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITLIST_DUPLICATE)
    }

    @Test
    @DisplayName("취소한 뒤에는 같은 구간을 다시 신청할 수 있고, 새 id로 새 행이 생긴다")
    fun apply_sameRangeAfterCancel_succeedsWithNewId() {
        val cancelled = waitlistService.apply(memberId, spaceId, startTime, endTime)
        waitlistService.cancel(memberId, cancelled.id)

        val reapplied = waitlistService.apply(memberId, spaceId, startTime, endTime)

        assertThat(reapplied.id).isNotEqualTo(cancelled.id)
        assertThat(reapplied.status).isEqualTo(WaitlistStatus.WAITING)
        assertThat(statusOfWaitlistEntry(cancelled.id)).isEqualTo("CANCELLED")
    }

    @Test
    @DisplayName("같은 구간의 취소·재신청을 반복해도 CANCELLED 이력이 쌓이고 활성은 항상 1건이다")
    fun apply_repeatedCancelAndReapply_keepsHistoryWithSingleActive() {
        val first = waitlistService.apply(memberId, spaceId, startTime, endTime)
        waitlistService.cancel(memberId, first.id)
        val second = waitlistService.apply(memberId, spaceId, startTime, endTime)
        waitlistService.cancel(memberId, second.id)
        val third = waitlistService.apply(memberId, spaceId, startTime, endTime)

        assertThat(countWaitlistEntries(memberId, "CANCELLED")).isEqualTo(2)
        assertThat(countWaitlistEntries(memberId, "WAITING")).isEqualTo(1)
        assertThat(waitlistService.getMyEntries(memberId))
            .singleElement()
            .extracting({ it.id })
            .isEqualTo(third.id)
    }

    @Test
    @DisplayName("활성 대기가 3건이면 4건째 신청을 WAITLIST_LIMIT_EXCEEDED로 거절한다")
    fun apply_overActiveLimit_rejected() {
        applyOccupiedRange(13, 0)
        applyOccupiedRange(16, 0)
        waitlistService.apply(memberId, spaceId, startTime, endTime)

        val fourthStart = tomorrowAt(17, 0)
        createReservation(occupierId, spaceId, ReservationStatus.CONFIRMED, fourthStart, fourthStart.plusMinutes(30))

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, fourthStart, fourthStart.plusMinutes(30)) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITLIST_LIMIT_EXCEEDED)
    }

    @Test
    @DisplayName("취소한 대기는 활성 건수에서 빠져 다시 3건까지 신청할 수 있다")
    fun apply_afterCancel_countsAgain() {
        val first = applyOccupiedRange(13, 0)
        applyOccupiedRange(16, 0)
        waitlistService.apply(memberId, spaceId, startTime, endTime)
        waitlistService.cancel(memberId, first.id)

        val fourthStart = tomorrowAt(17, 0)
        createReservation(occupierId, spaceId, ReservationStatus.CONFIRMED, fourthStart, fourthStart.plusMinutes(30))

        val fourth =
            waitlistService.apply(memberId, spaceId, fourthStart, fourthStart.plusMinutes(30))

        assertThat(fourth.status).isEqualTo(WaitlistStatus.WAITING)
    }

    @Test
    @DisplayName("취소 이력이 쌓여도 활성 3건 제한은 WAITING 행만 센다")
    fun apply_activeLimitCountsOnlyWaiting() {
        val first = applyOccupiedRange(13, 0)
        val second = applyOccupiedRange(16, 0)
        waitlistService.apply(memberId, spaceId, startTime, endTime)
        waitlistService.cancel(memberId, first.id)
        waitlistService.cancel(memberId, second.id)

        // CANCELLED 2건 + WAITING 1건 → 2건을 더 신청할 수 있다
        waitlistService.apply(memberId, spaceId, first.startTime, first.endTime)
        waitlistService.apply(memberId, spaceId, second.startTime, second.endTime)
        assertThat(countWaitlistEntries(memberId, "WAITING")).isEqualTo(3)

        val fourthStart = tomorrowAt(17, 0)
        createReservation(occupierId, spaceId, ReservationStatus.CONFIRMED, fourthStart, fourthStart.plusMinutes(30))

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, fourthStart, fourthStart.plusMinutes(30)) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITLIST_LIMIT_EXCEEDED)
    }

    @Test
    @DisplayName("구간이 이미 지난 WAITING 3건은 활성 제한을 차지하지 않아 미래 구간을 신청할 수 있다")
    fun apply_pastWaitingEntriesDoNotFillActiveLimit() {
        // EXPIRED 전이는 4단계 범위라 지난 구간의 WAITING 행이 남는다. 활성 제한은 startTime > now만 센다.
        for (day in 1..3) {
            insertWaitlistEntry(
                memberId,
                spaceId,
                yesterdayAt(14, 0).minusDays((day - 1).toLong()),
                yesterdayAt(15, 0).minusDays((day - 1).toLong()),
                "WAITING",
                1,
            )
        }

        val response = waitlistService.apply(memberId, spaceId, startTime, endTime)

        assertThat(response.status).isEqualTo(WaitlistStatus.WAITING)
    }

    @Test
    @DisplayName("구간이 이미 지난 WAITING은 내 목록에서 제외된다")
    fun getMyEntries_excludesPastRanges() {
        insertWaitlistEntry(memberId, spaceId, yesterdayAt(14, 0), yesterdayAt(15, 0), "WAITING", 1)
        val future = waitlistService.apply(memberId, spaceId, startTime, endTime)

        assertThat(waitlistService.getMyEntries(memberId))
            .singleElement()
            .extracting({ it.id })
            .isEqualTo(future.id)
    }

    @Test
    @DisplayName("신청 순서도 같은 기준이라, 시작 시각이 지난 선행 WAITING은 세지 않는다")
    fun apply_positionIgnoresPastStartedEntries() {
        // 시작은 지났지만 종료가 신청 구간과 겹치는 선행 행(id가 더 작다). 활성 기준이 startTime > now이므로 제외된다.
        insertWaitlistEntry(occupierId, spaceId, yesterdayAt(14, 0), endTime, "WAITING", 1)

        val response = waitlistService.apply(memberId, spaceId, startTime, endTime)

        assertThat(response.position).isEqualTo(1)
    }

    @Test
    @DisplayName("존재하지 않는 공간이면 SPACE_NOT_FOUND다")
    fun apply_spaceNotFound_rejected() {
        assertThatThrownBy { waitlistService.apply(memberId, 999_999_999L, startTime, endTime) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_NOT_FOUND)
    }

    @Test
    @DisplayName("비활성 공간이면 SPACE_INACTIVE다")
    fun apply_inactiveSpace_rejected() {
        jdbcTemplate.update("UPDATE spaces SET status = 'INACTIVE' WHERE id = ?", spaceId)

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, startTime, endTime) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_INACTIVE)
    }

    @Test
    @DisplayName("활성 3건 제한은 공간을 가로질러 적용된다(회원 전역)")
    fun apply_activeLimitAppliesAcrossSpaces() {
        for (i in 0 until 3) {
            val otherSpaceId = createSpace()
            createReservation(occupierId, otherSpaceId, ReservationStatus.CONFIRMED, startTime, endTime)
            waitlistService.apply(memberId, otherSpaceId, startTime, endTime)
        }

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, startTime, endTime) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITLIST_LIMIT_EXCEEDED)
    }

    @Test
    @DisplayName("status와 active가 어긋난 행은 CHECK 제약이 막는다")
    fun checkConstraint_rejectsStatusActiveMismatch() {
        // MySQL의 CHECK 위반(3819)은 Spring이 DataIntegrityViolationException이 아니라
        // UncategorizedSQLException으로 번역한다. 여기서 확인하려는 것은 "DB가 막는다"이므로
        // 두 예외의 공통 부모(DataAccessException)로 단정한다.
        assertThatThrownBy { insertWaitlistEntry(memberId, spaceId, startTime, endTime, "CANCELLED", 1) }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("chk_waitlist_entry_active")
        assertThatThrownBy { insertWaitlistEntry(memberId, spaceId, startTime, endTime, "WAITING", null) }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("chk_waitlist_entry_active")
    }

    @Test
    @DisplayName("정지된 회원은 대기 신청을 할 수 없다")
    fun apply_suspendedMember_rejected() {
        jdbcTemplate.update("UPDATE member SET status = 'SUSPENDED' WHERE id = ?", memberId)

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, startTime, endTime) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_INACTIVE)
    }

    @Test
    @DisplayName("탈퇴한 회원은 대기 신청을 할 수 없다")
    fun apply_withdrawnMember_rejected() {
        jdbcTemplate.update("UPDATE member SET status = 'WITHDRAWN' WHERE id = ?", memberId)

        assertThatThrownBy { waitlistService.apply(memberId, spaceId, startTime, endTime) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_WITHDRAWN)
    }

    // ---------- 취소 ----------

    @Test
    @DisplayName("본인 대기는 취소할 수 있고 상태가 CANCELLED로 바뀐다")
    fun cancel_byOwner_setsCancelled() {
        val entry = waitlistService.apply(memberId, spaceId, startTime, endTime)

        waitlistService.cancel(memberId, entry.id)

        assertThat(statusOfWaitlistEntry(entry.id)).isEqualTo("CANCELLED")
        assertThat(activeOfWaitlistEntry(entry.id)).isNull()
    }

    @Test
    @DisplayName("타인의 대기는 취소할 수 없다(FORBIDDEN_NOT_OWNER)")
    fun cancel_byOtherMember_rejected() {
        val entry = waitlistService.apply(memberId, spaceId, startTime, endTime)

        assertThatThrownBy { waitlistService.cancel(occupierId, entry.id) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER)
        assertThat(statusOfWaitlistEntry(entry.id)).isEqualTo("WAITING")
    }

    @Test
    @DisplayName("이미 취소된 대기를 다시 취소해도 예외 없이 CANCELLED 그대로다(멱등)")
    fun cancel_twice_isIdempotent() {
        val entry = waitlistService.apply(memberId, spaceId, startTime, endTime)
        waitlistService.cancel(memberId, entry.id)

        waitlistService.cancel(memberId, entry.id)

        assertThat(statusOfWaitlistEntry(entry.id)).isEqualTo("CANCELLED")
        assertThat(countWaitlistEntries(memberId, "CANCELLED")).isEqualTo(1)
    }

    @Test
    @DisplayName("존재하지 않는 대기 취소는 WAITLIST_NOT_FOUND다")
    fun cancel_notFound_rejected() {
        assertThatThrownBy { waitlistService.cancel(memberId, 999_999_999L) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITLIST_NOT_FOUND)
    }

    // ---------- 내 목록 ----------

    @Test
    @DisplayName("내 대기 목록은 신청 순서(겹치는 구간의 선행 WAITING 건수 + 1)를 함께 돌려준다")
    fun getMyEntries_includesPosition() {
        val earlierMemberId = createMember(BALANCE)
        val laterMemberId = createMember(BALANCE)
        waitlistService.apply(earlierMemberId, spaceId, startTime, endTime)
        val mine = waitlistService.apply(memberId, spaceId, startTime, startTime.plusMinutes(30))
        waitlistService.apply(laterMemberId, spaceId, startTime, endTime)
        // 겹치지 않는 구간은 별도 순서로 계산된다
        val otherStart = tomorrowAt(16, 0)
        createReservation(occupierId, spaceId, ReservationStatus.CONFIRMED, otherStart, otherStart.plusMinutes(30))
        waitlistService.apply(memberId, spaceId, otherStart, otherStart.plusMinutes(30))

        val entries = waitlistService.getMyEntries(memberId)

        assertThat(entries).hasSize(2)
        assertThat(entries[0].id).isEqualTo(mine.id)
        assertThat(entries[0].position).isEqualTo(2)
        assertThat(entries[1].position).isEqualTo(1)
    }

    @Test
    @DisplayName("취소한 대기는 내 목록에서 빠지고 다른 사람의 신청 순서도 당겨진다")
    fun getMyEntries_excludesCancelled() {
        val earlierMemberId = createMember(BALANCE)
        val first = waitlistService.apply(earlierMemberId, spaceId, startTime, endTime)
        val second = waitlistService.apply(memberId, spaceId, startTime, endTime)
        assertThat(second.position).isEqualTo(2)

        waitlistService.cancel(earlierMemberId, first.id)

        assertThat(waitlistService.getMyEntries(earlierMemberId)).isEmpty()
        assertThat(waitlistService.getMyEntries(memberId))
            .singleElement()
            .extracting({ it.position })
            .isEqualTo(1L)
    }

    // ---------- 헬퍼 ----------

    /** hour:minute부터 30분 구간을 타인 점유로 만든 뒤, 그 구간에 대기 신청한다. */
    private fun applyOccupiedRange(
        hour: Int,
        minute: Int,
    ): WaitlistEntryResponse {
        val start = tomorrowAt(hour, minute)
        val end = start.plusMinutes(30)
        createReservation(occupierId, spaceId, ReservationStatus.CONFIRMED, start, end)
        return waitlistService.apply(memberId, spaceId, start, end)
    }

    private fun statusOfWaitlistEntry(entryId: Long): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM waitlist_entry WHERE id = ?",
            String::class.java,
            entryId,
        )

    /** 어제의 hour:minute. 테스트 실행 시각과 무관하게 확실히 과거다(tomorrowAt의 반대). */
    private fun yesterdayAt(
        hour: Int,
        minute: Int,
    ): LocalDateTime =
        now()
            .minusDays(1)
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)

    private fun activeOfWaitlistEntry(entryId: Long): Int? =
        jdbcTemplate.queryForObject(
            "SELECT active FROM waitlist_entry WHERE id = ?",
            Int::class.javaObjectType,
            entryId,
        )

    /** 서비스를 거치지 않고 행을 직접 만든다(지난 구간, 제약 위반 등 서비스로는 만들 수 없는 상태용). */
    private fun insertWaitlistEntry(
        ownerId: Long,
        targetSpaceId: Long,
        start: LocalDateTime,
        end: LocalDateTime,
        status: String,
        active: Int?,
    ) {
        jdbcTemplate.update(
            "INSERT INTO waitlist_entry" +
                " (member_id, space_id, start_time, end_time, status, active, created_at)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?)",
            ownerId,
            targetSpaceId,
            start,
            end,
            status,
            active,
            now(),
        )
    }

    private fun countWaitlistEntries(
        ownerId: Long,
        status: String,
    ): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM waitlist_entry WHERE member_id = ? AND status = ?",
            Int::class.javaObjectType,
            ownerId,
            status,
        )

    private companion object {
        private const val BALANCE = 100_000
    }
}
