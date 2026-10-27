package com.ovengers.slotkey.waitlist.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException

/**
 * 제약 위반을 `WAITLIST_DUPLICATE`로 바꾸는 범위가 활성 UNIQUE 하나로 좁혀졌는지 본다.
 * CHECK 위반까지 409 "이미 신청한 대기입니다"로 바뀌면 4단계에서 진짜 원인이 가려진다(step-1-review.md M2).
 */
@DisplayName("대기 신청 중복 매핑 판정")
class WaitlistDuplicateMappingTest {
    private fun violation(causeMessage: String?): DataIntegrityViolationException =
        DataIntegrityViolationException(
            "could not execute statement",
            SQLException(causeMessage, "23000", 1062),
        )

    @Test
    @DisplayName("활성 UNIQUE 위반만 중복으로 본다")
    fun activeUniqueViolation_isDuplicate() {
        val e =
            violation(
                "Duplicate entry '7-3-2026-10-20 14:00:00-2026-10-20 15:00:00-1' for key" +
                    " 'waitlist_entry.uk_waitlist_entry_member_space_range_active'",
            )

        assertThat(WaitlistService.isActiveUniqueViolation(e)).isTrue()
    }

    @Test
    @DisplayName("CHECK 제약 위반은 중복이 아니다(그대로 전파되어야 한다)")
    fun checkConstraintViolation_isNotDuplicate() {
        val e =
            violation(
                "Check constraint 'chk_waitlist_entry_active' is violated.",
            )

        assertThat(WaitlistService.isActiveUniqueViolation(e)).isFalse()
    }

    @Test
    @DisplayName("시간 범위 CHECK 위반도 중복이 아니다")
    fun timeRangeConstraintViolation_isNotDuplicate() {
        val e =
            violation(
                "Check constraint 'chk_waitlist_entry_time_range' is violated.",
            )

        assertThat(WaitlistService.isActiveUniqueViolation(e)).isFalse()
    }

    @Test
    @DisplayName("제약명을 알 수 없는 메시지는 중복으로 보지 않는다")
    fun unknownMessage_isNotDuplicate() {
        assertThat(WaitlistService.isActiveUniqueViolation(violation(null))).isFalse()
        assertThat(WaitlistService.isActiveUniqueViolation(violation("deadlock found"))).isFalse()
    }
}
