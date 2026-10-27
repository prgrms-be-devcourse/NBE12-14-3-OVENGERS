package com.ovengers.slotkey.reservation.policy

import com.ovengers.slotkey.credit.pg.PgErrorClassifier
import com.ovengers.slotkey.support.nullValue
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime
import java.time.LocalTime

class NonNullPolicyContractTest {
    @Test
    @DisplayName("Java 호출에서도 예약 시간 인자는 모두 필수다")
    fun validate_nullArgument_failsFast() {
        assertThatThrownBy({ ReservationTimePolicy.validate(nullValue(), END, OPENING, CLOSING, NOW) })
            .isInstanceOf(NullPointerException::class.java)
        assertThatThrownBy({ ReservationTimePolicy.validate(START, nullValue(), OPENING, CLOSING, NOW) })
            .isInstanceOf(NullPointerException::class.java)
        assertThatThrownBy({ ReservationTimePolicy.validate(START, END, nullValue(), CLOSING, NOW) })
            .isInstanceOf(NullPointerException::class.java)
        assertThatThrownBy({ ReservationTimePolicy.validate(START, END, OPENING, nullValue(), NOW) })
            .isInstanceOf(NullPointerException::class.java)
        assertThatThrownBy({ ReservationTimePolicy.validate(START, END, OPENING, CLOSING, nullValue()) })
            .isInstanceOf(NullPointerException::class.java)
    }

    @Test
    @DisplayName("Java 호출에서도 연장 시간 인자는 모두 필수다")
    fun validateExtension_nullArgument_failsFast() {
        assertThatThrownBy({ ReservationTimePolicy.validateExtension(nullValue(), END, CLOSING) })
            .isInstanceOf(NullPointerException::class.java)
        assertThatThrownBy({ ReservationTimePolicy.validateExtension(START, nullValue(), CLOSING) })
            .isInstanceOf(NullPointerException::class.java)
        assertThatThrownBy({ ReservationTimePolicy.validateExtension(START, END, nullValue()) })
            .isInstanceOf(NullPointerException::class.java)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 500])
    @DisplayName("Java 호출에서도 PG 분류기의 오류 코드는 필수다")
    fun classify_nullCode_failsFast(httpStatus: Int) {
        assertThatThrownBy({ PgErrorClassifier.classify(httpStatus, nullValue()) })
            .isInstanceOf(NullPointerException::class.java)
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 17, 10, 0)
        private val START: LocalDateTime = NOW.plusHours(4)
        private val END: LocalDateTime = START.plusMinutes(30)
        private val OPENING: LocalTime = LocalTime.of(9, 0)
        private val CLOSING: LocalTime = LocalTime.of(22, 0)
    }
}
