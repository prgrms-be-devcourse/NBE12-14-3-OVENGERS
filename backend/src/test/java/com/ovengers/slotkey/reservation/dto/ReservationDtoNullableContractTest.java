package com.ovengers.slotkey.reservation.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.reservation.dto.request.ReservationPayRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class ReservationDtoNullableContractTest {
    @Autowired private ObjectMapper objectMapper;

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"spaceVersion\":null}"})
    void missingVersionIsNullableAndStillFailsRequiredValidation(String json) throws Exception {
        ReservationPayRequest request = objectMapper.readValue(json, ReservationPayRequest.class);

        assertThat(ReservationPayRequest.class.getRecordComponents()[0].getType()).isEqualTo(Integer.class);
        assertThat(request.spaceVersion()).isNull();

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("spaceVersion");
        }
    }
}
