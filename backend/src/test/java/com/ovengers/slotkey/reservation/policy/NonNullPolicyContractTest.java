package com.ovengers.slotkey.reservation.policy;

import com.ovengers.slotkey.credit.pg.PgErrorClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NonNullPolicyContractTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 10, 0);
    private static final LocalDateTime START = NOW.plusHours(4);
    private static final LocalDateTime END = START.plusMinutes(30);
    private static final LocalTime OPENING = LocalTime.of(9, 0);
    private static final LocalTime CLOSING = LocalTime.of(22, 0);

    @Test
    @DisplayName("Java 호출에서도 예약 시간 인자는 모두 필수다")
    void validate_nullArgument_failsFast() {
        assertThatThrownBy(() -> ReservationTimePolicy.validate(null, END, OPENING, CLOSING, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReservationTimePolicy.validate(START, null, OPENING, CLOSING, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReservationTimePolicy.validate(START, END, null, CLOSING, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReservationTimePolicy.validate(START, END, OPENING, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReservationTimePolicy.validate(START, END, OPENING, CLOSING, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Java 호출에서도 연장 시간 인자는 모두 필수다")
    void validateExtension_nullArgument_failsFast() {
        assertThatThrownBy(() -> ReservationTimePolicy.validateExtension(null, END, CLOSING))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReservationTimePolicy.validateExtension(START, null, CLOSING))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReservationTimePolicy.validateExtension(START, END, null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500})
    @DisplayName("Java 호출에서도 PG 분류기의 오류 코드는 필수다")
    void classify_nullCode_failsFast(int httpStatus) {
        assertThatThrownBy(() -> PgErrorClassifier.classify(httpStatus, null))
                .isInstanceOf(NullPointerException.class);
    }
}
