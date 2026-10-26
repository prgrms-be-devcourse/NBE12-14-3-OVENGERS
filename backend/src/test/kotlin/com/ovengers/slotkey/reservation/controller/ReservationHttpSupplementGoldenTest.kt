package com.ovengers.slotkey.reservation.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.global.security.jwt.JwtProvider
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.support.SurveyGoldenAssertions.readGolden
import com.ovengers.slotkey.support.SurveyGoldenClockConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets.UTF_8

@AutoConfigureMockMvc
@Import(SurveyGoldenClockConfig::class)
class ReservationHttpSupplementGoldenTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("pay,200", "extend,200", "force-cancel,200", "expired-pay,409")
    fun responseBodyKeepsJavaHttpOutput(
        situation: String,
        httpStatus: Int,
    ) {
        val memberId = createMember(30000)
        val spaceId = createSpace()
        jdbcTemplate.update("UPDATE member SET email = 'golden-owner@slotkey.test' WHERE id = ?", memberId)
        jdbcTemplate.update("UPDATE spaces SET name = 'golden-space' WHERE id = ?", spaceId)
        val initial = if (situation.endsWith("pay")) ReservationStatus.HELD else ReservationStatus.CONFIRMED
        val start = now().plusDays(2).withHour(10)
        val end = start.plusHours(1)
        val reservationId = createReservation(memberId, spaceId, initial, start, end)
        if (situation == "expired-pay") {
            jdbcTemplate.update("UPDATE reservation SET hold_expires_at = ? WHERE id = ?", now().minusMinutes(1), reservationId)
        }
        val actorId =
            if (situation == "force-cancel") {
                val adminId = createMember(0)
                jdbcTemplate.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", adminId)
                adminId
            } else {
                memberId
            }
        val token = issueSessionBoundAccessToken(memberRepository.findById(actorId).orElseThrow(), jwtProvider)
        val endpoint =
            when (situation) {
                "force-cancel" -> "/api/v1/admin/reservations/$reservationId/force-cancel"
                "extend" -> "/api/v1/reservations/$reservationId/extend"
                else -> "/api/v1/reservations/$reservationId/pay"
            }
        val body =
            when (situation) {
                "force-cancel" -> """{"reason":"golden cancellation"}"""
                "extend" -> mapper.writeValueAsString(mapOf("expectedEndTime" to end, "newEndTime" to end.plusMinutes(30)))
                else -> """{"spaceVersion":0}"""
            }
        val request =
            post(endpoint)
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", "golden-$situation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        val response =
            mvc
                .perform(request)
                .andExpect(status().`is`(httpStatus))
                .andReturn()
                .response
        val actual = mapper.readTree(response.getContentAsString(UTF_8))
        val fixture = "reservation/$situation-${if (httpStatus == 409) "conflict" else "success"}"
        val expected = readGolden(mapper, fixture)
        assertFields(actual, expected)
        for ((field, value) in expected.fields()) {
            if (field == "data") {
                val actualData = actual[field]
                assertFields(actualData, value)
                val generatedIds = mapOf("reservationId" to reservationId, "spaceId" to spaceId, "memberId" to memberId)
                for ((dataField, dataValue) in value.fields()) {
                    if (generatedIds.containsKey(dataField)) {
                        assertThat(actualData[dataField].isIntegralNumber).describedAs(dataField).isTrue()
                        assertThat(actualData[dataField].longValue()).isEqualTo(generatedIds[dataField])
                    } else {
                        assertThat(actualData[dataField]).describedAs("data.$dataField").isEqualTo(dataValue)
                    }
                }
                assertThat(actualData["totalAmount"].isIntegralNumber).isTrue()
            } else {
                assertThat(actual[field]).describedAs(field).isEqualTo(value)
            }
        }
        when (situation) {
            "pay" -> {
                assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED")
                assertThat(balanceOf(memberId)).isEqualTo(20000)
                val replay =
                    mvc
                        .perform(request)
                        .andExpect(status().isOk)
                        .andReturn()
                        .response
                assertThat(mapper.readTree(replay.getContentAsString(UTF_8))).isEqualTo(actual)
                assertThat(balanceOf(memberId)).isEqualTo(20000)
                assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isEqualTo(1)
            }
            "extend" -> {
                assertThat(statusOf(reservationId)).isEqualTo("CONFIRMED")
                assertThat(balanceOf(memberId)).isEqualTo(25000)
                assertThat(countSlots(reservationId)).isEqualTo(3)
            }
            "force-cancel" -> {
                assertThat(statusOf(reservationId)).isEqualTo("CANCELLED")
                assertThat(balanceOf(memberId)).isEqualTo(40000)
                assertThat(countSlots(reservationId)).isZero()
                assertThat(countLedger(reservationId, "REFUND")).isEqualTo(1)
            }
            "expired-pay" -> {
                assertThat(statusOf(reservationId)).isEqualTo("HELD")
                assertThat(balanceOf(memberId)).isEqualTo(30000)
                assertThat(countSlots(reservationId)).isEqualTo(2)
                assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isZero()
                assertThat(countIdempotencyKeys(memberId)).isZero()
            }
        }
    }

    private fun assertFields(
        actual: JsonNode,
        expected: JsonNode,
    ) {
        assertThat(actual.isObject).isTrue()
        assertThat(actual.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrderElementsOf(expected.fieldNames().asSequence().toList())
    }
}
