package com.ovengers.slotkey.reservation.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.reservation.dto.request.ReservationPayRequest
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest

@JsonTest
class ReservationDtoNullableContractTest {
    @Autowired private lateinit var objectMapper: ObjectMapper

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"spaceVersion\":null}"])
    fun missingVersionIsNullableAndStillFailsRequiredValidation(json: String) {
        val request: ReservationPayRequest = objectMapper.readValue(json, ReservationPayRequest::class.java)

        assertThat(ReservationPayRequest::class.java.recordComponents[0].type).isEqualTo(Int::class.javaObjectType)
        assertThat(request.spaceVersion).isNull()

        Validation.buildDefaultValidatorFactory().use { factory ->
            val violations = factory.validator.validate(request)
            assertThat(violations).hasSize(1)
            assertThat(
                violations
                    .iterator()
                    .next()
                    .propertyPath
                    .toString(),
            ).isEqualTo("spaceVersion")
        }
    }
}
