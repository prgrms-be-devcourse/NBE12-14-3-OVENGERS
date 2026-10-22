package com.ovengers.slotkey.reservation.policy

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

class ReservationTimePolicyTest {
    private val opening = LocalTime.of(9, 0)
    private val closing = LocalTime.of(22, 0)
    private val now = LocalDateTime.of(2026, 9, 17, 10, 0)

    @Test
    @DisplayName("종료 시각이 시작 시각보다 늦지 않으면 INVALID_RESERVATION_TIME 예외가 발생한다")
    fun validate_endNotAfterStart_throwsInvalidTime() {
        val start = LocalDateTime.of(2026, 9, 17, 14, 0)
        val endEqual = LocalDateTime.of(2026, 9, 17, 14, 0)
        val endBefore = LocalDateTime.of(2026, 9, 17, 13, 30)

        assertThatThrownBy { ReservationTimePolicy.validate(start, endEqual, opening, closing, now) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)

        assertThatThrownBy { ReservationTimePolicy.validate(start, endBefore, opening, closing, now) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("현재 시각과 같거나 그 이전(과거) 시각으로는 예약할 수 없어 INVALID_RESERVATION_TIME 예외가 발생한다")
    fun validate_notFutureStart_throwsInvalidTime() {
        val start = now // now와 같은 시각 -> 미래가 아님
        val end = now.plusMinutes(30)

        assertThatThrownBy { ReservationTimePolicy.validate(start, end, opening, closing, now) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("30분 단위(분·초·나노초)가 아닌 시각이면 INVALID_RESERVATION_TIME 예외가 발생한다")
    fun validate_notSlotAligned_throwsInvalidTime() {
        val start = LocalDateTime.of(2026, 9, 17, 14, 15) // 15분 -> 30분 배수 아님
        val end = LocalDateTime.of(2026, 9, 17, 14, 45)

        assertThatThrownBy { ReservationTimePolicy.validate(start, end, opening, closing, now) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("시작과 종료가 서로 다른 날짜면 INVALID_RESERVATION_TIME 예외가 발생한다")
    fun validate_differentDate_throwsInvalidTime() {
        val start = LocalDateTime.of(2026, 9, 17, 21, 0)
        val end = LocalDateTime.of(2026, 9, 18, 21, 30)

        assertThatThrownBy { ReservationTimePolicy.validate(start, end, opening, closing, now) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("공간 운영시간을 벗어나면 INVALID_RESERVATION_TIME 예외가 발생한다")
    fun validate_outsideOperatingHours_throwsInvalidTime() {
        val start = LocalDateTime.of(2026, 9, 18, 8, 30) // 오픈(9시) 전
        val end = LocalDateTime.of(2026, 9, 18, 9, 0)

        assertThatThrownBy { ReservationTimePolicy.validate(start, end, opening, closing, now) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("모든 조건을 만족하는 예약 시간은 예외 없이 통과한다")
    fun validate_validTime_doesNotThrow() {
        val start = LocalDateTime.of(2026, 9, 17, 14, 0)
        val end = LocalDateTime.of(2026, 9, 17, 15, 0)

        assertThatCode { ReservationTimePolicy.validate(start, end, opening, closing, now) }
            .doesNotThrowAnyException()
    }

    // ---------- validateExtension ----------

    private val extendFrom = LocalDateTime.of(2026, 9, 18, 21, 0)

    @Test
    @DisplayName("연장 종료 시각이 운영 종료 시각과 정확히 같으면 통과한다")
    fun validateExtension_untilExactlyClosing_doesNotThrow() {
        val newEnd = LocalDateTime.of(2026, 9, 18, 22, 0)

        assertThatCode { ReservationTimePolicy.validateExtension(extendFrom, newEnd, closing) }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("연장 종료 시각이 운영 종료를 한 슬롯(30분)이라도 넘으면 INVALID_RESERVATION_TIME 예외가 발생한다")
    fun validateExtension_oneSlotPastClosing_throwsInvalidTime() {
        val newEnd = LocalDateTime.of(2026, 9, 18, 22, 30)

        assertThatThrownBy { ReservationTimePolicy.validateExtension(extendFrom, newEnd, closing) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("연장 종료 시각이 30분 단위가 아니면(분이 어긋나거나 초가 있으면) INVALID_RESERVATION_TIME 예외가 발생한다")
    fun validateExtension_unalignedEndTime_throwsInvalidTime() {
        val unalignedMinute = LocalDateTime.of(2026, 9, 18, 21, 15)
        val withSeconds = LocalDateTime.of(2026, 9, 18, 21, 30, 10)

        assertThatThrownBy { ReservationTimePolicy.validateExtension(extendFrom, unalignedMinute, closing) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
        assertThatThrownBy { ReservationTimePolicy.validateExtension(extendFrom, withSeconds, closing) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("연장으로 날짜가 넘어가면(자정 포함) INVALID_RESERVATION_TIME 예외가 발생한다")
    fun validateExtension_crossesDate_throwsInvalidTime() {
        val midnight = LocalDateTime.of(2026, 9, 19, 0, 0)
        val nextDay = LocalDateTime.of(2026, 9, 19, 10, 0)

        assertThatThrownBy { ReservationTimePolicy.validateExtension(extendFrom, midnight, closing) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
        assertThatThrownBy { ReservationTimePolicy.validateExtension(extendFrom, nextDay, closing) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("연장 종료 시각이 기존 종료 시각과 같거나 이르면 VALIDATION_FAILED 예외가 발생한다")
    fun validateExtension_notAfterCurrentEnd_throwsValidationFailed() {
        assertThatThrownBy { ReservationTimePolicy.validateExtension(extendFrom, extendFrom, closing) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED)
        assertThatThrownBy {
            ReservationTimePolicy.validateExtension(extendFrom, extendFrom.minusMinutes(30), closing)
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED)
    }

    @Test
    @DisplayName("운영 시간 안에서 30분 단위로 같은 날 연장하면 예외 없이 통과한다")
    fun validateExtension_validExtension_doesNotThrow() {
        val newEnd = LocalDateTime.of(2026, 9, 18, 21, 30)

        assertThatCode { ReservationTimePolicy.validateExtension(extendFrom, newEnd, closing) }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("예약 시작과 종료의 초 또는 나노초가 있으면 거절한다")
    fun validate_secondsOrNanos_rejectsUnalignedTime() {
        val start = now.plusHours(4)
        val end = start.plusMinutes(30)

        for (unalignedStart in listOf(start.plusSeconds(1), start.plusNanos(1))) {
            assertThatThrownBy { ReservationTimePolicy.validate(unalignedStart, end, opening, closing, now) }
                .isInstanceOf(BusinessException::class.java)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
        }
        for (unalignedEnd in listOf(end.plusSeconds(1), end.plusNanos(1))) {
            assertThatThrownBy { ReservationTimePolicy.validate(start, unalignedEnd, opening, closing, now) }
                .isInstanceOf(BusinessException::class.java)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
        }
    }

    @Test
    @DisplayName("기존 종료와 연장 종료의 나노초가 있으면 거절한다")
    fun validateExtension_nanos_rejectsUnalignedTime() {
        val newEnd = extendFrom.plusMinutes(30)

        assertThatThrownBy { ReservationTimePolicy.validateExtension(extendFrom.plusNanos(1), newEnd, closing) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
        assertThatThrownBy { ReservationTimePolicy.validateExtension(extendFrom, newEnd.plusNanos(1), closing) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_TIME)
    }

    @Test
    @DisplayName("예약 시간 오류는 Kotlin 호출부에서도 기존 오류 코드와 상세를 제공한다")
    fun validateExtension_invalidRange_preservesBusinessError() {
        val exception =
            catchThrowableOfType(
                { ReservationTimePolicy.validateExtension(extendFrom, extendFrom, closing) },
                BusinessException::class.java,
            )

        assertThat(exception.errorCode).isEqualTo(ErrorCode.VALIDATION_FAILED)
        assertThat(exception.message).isEqualTo("연장 시각은 기존 종료 시각보다 늦어야 합니다.")
    }
}
