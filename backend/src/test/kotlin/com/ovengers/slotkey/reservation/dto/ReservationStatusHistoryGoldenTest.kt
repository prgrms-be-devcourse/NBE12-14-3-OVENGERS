package com.ovengers.slotkey.reservation.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.reservation.dto.response.ReservationStatusHistoryResponse
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import java.time.LocalDateTime

@JsonTest
class ReservationStatusHistoryGoldenTest {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("예약 상태 이력 JSON이 Java 원본과 동일하다")
    fun statusHistoryMatchesJavaGolden() {
        // given: Java 원본과 동일한 상태 이력 3건
        val createdAt = LocalDateTime.of(2026, 10, 23, 9, 0)

        val histories =
            listOf(
                ReservationStatusHistory.of(
                    10L,
                    901L,
                    null,
                    ReservationStatus.HELD,
                    null,
                    createdAt,
                ),
                ReservationStatusHistory.of(
                    10L,
                    902L,
                    ReservationStatus.HELD,
                    ReservationStatus.CONFIRMED,
                    "결제 완료",
                    createdAt.plusMinutes(5),
                ),
                ReservationStatusHistory.of(
                    10L,
                    903L,
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.CANCELLED,
                    "사용자 취소",
                    createdAt.plusMinutes(30),
                ),
            )

        // when: 상태 이력 DTO만 변환하고 JSON 직렬화
        val responses =
            histories.map { history ->
                ReservationStatusHistoryResponse.from(history)
            }

        val actual =
            objectMapper.readTree(
                objectMapper.writeValueAsString(responses),
            )

        // then: Java 원본 골든 JSON과 비교
        val goldenStream =
            requireNotNull(
                javaClass.getResourceAsStream(
                    "/golden/reservation/status-history-transitions.json",
                ),
            ) {
                "Java 원본 골든 JSON 파일이 없습니다."
            }

        val expected = goldenStream.use { objectMapper.readTree(it) }

        assertThat(actual).isEqualTo(expected)

        // 주요 계약 별도 검증
        assertThat(actual.isArray).isTrue()
        assertThat(actual.size()).isEqualTo(3)

        assertThat(actual[0].path("fromStatus").isNull).isTrue()
        assertThat(actual[0].path("reason").isNull).isTrue()

        assertThat(actual[1].path("fromStatus").asText()).isEqualTo("HELD")
        assertThat(actual[1].path("toStatus").asText()).isEqualTo("CONFIRMED")

        assertThat(actual[2].path("toStatus").asText()).isEqualTo("CANCELLED")
        assertThat(actual[2].path("changedAt").asText())
            .isEqualTo("2026-10-23T09:30:00")

        // 변경 주체 ID는 사용자 응답에 노출하지 않음
        assertThat(actual.findValues("changedByMemberId")).isEmpty()
    }
}
