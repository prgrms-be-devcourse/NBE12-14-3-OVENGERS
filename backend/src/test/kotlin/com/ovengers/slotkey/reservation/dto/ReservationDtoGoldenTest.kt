package com.ovengers.slotkey.reservation.dto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.reservation.dto.request.ReservationCreateRequest
import com.ovengers.slotkey.reservation.dto.response.AdminReservationDetailResponse
import com.ovengers.slotkey.reservation.dto.response.AdminReservationResponse
import com.ovengers.slotkey.reservation.dto.response.ReservationDetailResponse
import com.ovengers.slotkey.reservation.dto.response.ReservationListResponse
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import java.time.LocalDateTime

/** Java c763cb8에서 고정한 JSON을 Kotlin 전환 후에도 같은 파일로 비교한다. */
@JsonTest
class ReservationDtoGoldenTest {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @ParameterizedTest
    @CsvSource(
        value = [
            "hold-response, HELD", "confirmed-response, CONFIRMED", "cancelled-response, CANCELLED",
            "in-use-response, IN_USE", "completed-response, COMPLETED",
            "detail-empty-history, CONFIRMED", "list-missing-space, CONFIRMED",
            "admin-missing-related-data, CONFIRMED", "admin-detail-empty-history, CONFIRMED",
        ],
    )
    fun responseMatchesJavaGolden(
        fixture: String,
        status: ReservationStatus,
    ) {
        val reservation: Reservation = reservation(status)
        val response: Any =
            when (fixture) {
                "hold-response" -> ReservationResponse.from(reservation, 0)
                "cancelled-response" -> ReservationResponse.ofCancelled(reservation, 0, 2000)
                "detail-empty-history" -> ReservationDetailResponse.of(reservation, listOf())
                "list-missing-space" -> ReservationListResponse.from(reservation, null)
                "admin-missing-related-data" -> AdminReservationResponse.from(reservation, null, null)
                "admin-detail-empty-history" ->
                    AdminReservationDetailResponse.from(
                        reservation,
                        null,
                        null,
                        listOf(),
                        listOf(),
                    )
                else -> ReservationResponse.from(reservation)
            }
        javaClass.getResourceAsStream("/golden/reservation/" + fixture + ".json").use { expected ->
            assertThat(expected).`as`("Java 기준 파일: %s", fixture).isNotNull()
            val actual: JsonNode = objectMapper.readTree(objectMapper.writeValueAsString(response))
            assertThat(actual).isEqualTo(objectMapper.readTree(expected))
            assertThat(actual.get("reservationId").isIntegralNumber).isTrue()
            assertThat(actual.get("totalAmount").isIntegralNumber).isTrue()
        }
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "ReservationCreateRequest, create-request", "ReservationExtendRequest, extend-request",
            "ReservationPayRequest, pay-request", "ForceCancelRequest, force-cancel-request",
            "ReservationSearchCondition, search-condition", "AdminReservationSearchCondition, admin-search-condition",
        ],
    )
    fun requestRoundTripKeepsFieldNamesAndTypes(
        className: String,
        fixture: String,
    ) {
        val type: Class<*> = Class.forName(ReservationCreateRequest::class.java.packageName + "." + className)
        javaClass.getResourceAsStream("/golden/reservation/" + fixture + ".json").use { input ->
            assertThat(input).isNotNull()
            val expected: JsonNode = objectMapper.readTree(input)
            val value: Any = objectMapper.treeToValue(expected, type)
            assertThat(objectMapper.readTree(objectMapper.writeValueAsString(value))).isEqualTo(expected)
        }
    }

    companion object {
        @JvmStatic
        fun reservation(status: ReservationStatus): Reservation {
            val created: LocalDateTime = LocalDateTime.of(2026, 10, 23, 9, 0)
            val start: LocalDateTime = LocalDateTime.of(2026, 10, 25, 10, 0)
            return Reservation
                .builder()
                .id(10L)
                .memberId(1L)
                .spaceId(20L)
                .startTime(start)
                .endTime(start.plusHours(1))
                .status(status)
                .pricePerSlotSnapshot(1000)
                .totalAmount(2000)
                .createdAt(created)
                .holdExpiresAt(if (status == ReservationStatus.HELD) created.plusMinutes(10) else null)
                .checkedInAt(
                    if (status == ReservationStatus.IN_USE ||
                        status == ReservationStatus.COMPLETED
                    ) {
                        start.plusMinutes(5)
                    } else {
                        null
                    },
                ).checkedOutAt(if (status == ReservationStatus.COMPLETED) start.plusMinutes(40) else null)
                .cancelledAt(if (status == ReservationStatus.CANCELLED) created.plusHours(1) else null)
                .build()
        }
    }
}
