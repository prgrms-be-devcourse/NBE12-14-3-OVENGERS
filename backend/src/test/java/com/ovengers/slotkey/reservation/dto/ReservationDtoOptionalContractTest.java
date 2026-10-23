package com.ovengers.slotkey.reservation.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.reservation.dto.request.ReservationPayRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class ReservationDtoOptionalContractTest {
    @Autowired private ObjectMapper objectMapper;

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"spaceVersion\":null}"})
    void missingVersionIsRepresentedWithoutNullAndStillFailsRequiredValidation(String json) throws Exception {
        ReservationPayRequest request = objectMapper.readValue(json, ReservationPayRequest.class);
        Object value = request.getClass().getMethod("spaceVersion").invoke(request);
        assertThat(value).isInstanceOf(Optional.class);
        assertThat((Optional<?>) value).isEmpty();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("spaceVersion");
        }
    }
}
