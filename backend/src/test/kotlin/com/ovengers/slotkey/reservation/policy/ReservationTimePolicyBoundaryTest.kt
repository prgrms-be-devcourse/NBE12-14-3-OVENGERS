package com.ovengers.slotkey.reservation.policy

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 뮤테이션 테스트(PIT)에서 살아남은 뮤턴트를 죽이기 위한 운영시간 경계 테스트.
 * 근거: docs/lab/notes/mutation-testing/step-2.md
 */
class ReservationTimePolicyBoundaryTest {
    private val opening = LocalTime.of(9, 0)
    private val closing = LocalTime.of(22, 0)
    private val now = LocalDateTime.of(2026, 9, 17, 10, 0)

    @Test
    @DisplayName("종료 시각이 운영 종료 시각을 넘으면 INVALID_RESERVATION_TIME 예외가 발생한다")
    fun validate_endAfterClosing_throwsInvalidTime() {
        val start = LocalDateTime.of(2026, 9, 18, 21, 30)
        val end = LocalDateTime.of(2026, 9, 18, 22, 30)

        assertThatThrownBy { ReservationTimePolicy.validate(start, end, opening, closing, now) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("종료 시각이 운영 종료 시각과 정확히 같으면 예외 없이 통과한다")
    fun validate_endExactlyAtClosing_doesNotThrow() {
        val start = LocalDateTime.of(2026, 9, 18, 21, 30)
        val end = LocalDateTime.of(2026, 9, 18, 22, 0)

        assertThatCode { ReservationTimePolicy.validate(start, end, opening, closing, now) }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("시작 시각이 운영 시작 시각과 정확히 같으면 예외 없이 통과한다")
    fun validate_startExactlyAtOpening_doesNotThrow() {
        val start = LocalDateTime.of(2026, 9, 18, 9, 0)
        val end = LocalDateTime.of(2026, 9, 18, 9, 30)

        assertThatCode { ReservationTimePolicy.validate(start, end, opening, closing, now) }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("시작 시각이 운영 시작 전이면 종료 시각이 운영시간 안이어도 예외가 발생한다")
    fun validate_startBeforeOpeningEndInside_throwsInvalidTime() {
        val start = LocalDateTime.of(2026, 9, 18, 8, 30)
        val end = LocalDateTime.of(2026, 9, 18, 9, 30)

        assertThatThrownBy { ReservationTimePolicy.validate(start, end, opening, closing, now) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }
}
