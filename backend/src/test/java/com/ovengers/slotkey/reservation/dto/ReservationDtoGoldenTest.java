package com.ovengers.slotkey.reservation.dto;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ovengers.slotkey.reservation.dto.request.*;
import com.ovengers.slotkey.reservation.dto.response.*;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Java c763cb8에서 고정한 JSON을 Kotlin 전환 후에도 같은 파일로 비교한다. */
@JsonTest
class ReservationDtoGoldenTest {
    @Autowired
    private ObjectMapper objectMapper;

    @ParameterizedTest
    @CsvSource({
            "hold-response, HELD", "confirmed-response, CONFIRMED", "cancelled-response, CANCELLED",
            "in-use-response, IN_USE", "completed-response, COMPLETED",
            "detail-empty-history, CONFIRMED", "list-missing-space, CONFIRMED",
            "admin-missing-related-data, CONFIRMED", "admin-detail-empty-history, CONFIRMED"
    })
    void responseMatchesJavaGolden(String fixture, ReservationStatus status) throws Exception {
        Reservation reservation = reservation(status);
        Object response = switch (fixture) {
            case "hold-response" -> ReservationResponse.from(reservation, 0);
            case "cancelled-response" -> ReservationResponse.ofCancelled(reservation, 0, 2000);
            case "detail-empty-history" -> ReservationDetailResponse.of(reservation, List.of());
            case "list-missing-space" -> ReservationListResponse.from(reservation, null);
            case "admin-missing-related-data" -> AdminReservationResponse.from(reservation, null, null);
            case "admin-detail-empty-history" -> AdminReservationDetailResponse.from(
                    reservation, null, null, List.of(), List.of());
            default -> ReservationResponse.from(reservation);
        };
        try (InputStream expected = getClass().getResourceAsStream("/golden/reservation/" + fixture + ".json")) {
            assertThat(expected).as("Java 기준 파일: %s", fixture).isNotNull();
            JsonNode actual = objectMapper.readTree(objectMapper.writeValueAsString(response));
            assertThat(actual).isEqualTo(objectMapper.readTree(expected));
            assertThat(actual.get("reservationId").isIntegralNumber()).isTrue();
            assertThat(actual.get("totalAmount").isIntegralNumber()).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "ReservationCreateRequest, create-request", "ReservationExtendRequest, extend-request",
            "ReservationPayRequest, pay-request", "ForceCancelRequest, force-cancel-request",
            "ReservationSearchCondition, search-condition", "AdminReservationSearchCondition, admin-search-condition"
    })
    void requestRoundTripKeepsFieldNamesAndTypes(String className, String fixture) throws Exception {
        Class<?> type = Class.forName(ReservationCreateRequest.class.getPackageName() + "." + className);
        try (InputStream input = getClass().getResourceAsStream("/golden/reservation/" + fixture + ".json")) {
            assertThat(input).isNotNull();
            JsonNode expected = objectMapper.readTree(input);
            Object value = objectMapper.treeToValue(expected, type);
            assertThat(objectMapper.readTree(objectMapper.writeValueAsString(value))).isEqualTo(expected);
        }
    }

    public static Reservation reservation(ReservationStatus status) {
        LocalDateTime created = LocalDateTime.of(2026, 10, 23, 9, 0);
        LocalDateTime start = LocalDateTime.of(2026, 10, 25, 10, 0);
        return Reservation.builder().id(10L).memberId(1L).spaceId(20L)
                .startTime(start).endTime(start.plusHours(1)).status(status)
                .pricePerSlotSnapshot(1000).totalAmount(2000).createdAt(created)
                .holdExpiresAt(status == ReservationStatus.HELD ? created.plusMinutes(10) : null)
                .checkedInAt(status == ReservationStatus.IN_USE || status == ReservationStatus.COMPLETED
                        ? start.plusMinutes(5) : null)
                .checkedOutAt(status == ReservationStatus.COMPLETED ? start.plusMinutes(40) : null)
                .cancelledAt(status == ReservationStatus.CANCELLED ? created.plusHours(1) : null)
                .build();
    }
}
