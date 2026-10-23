package com.ovengers.slotkey.space.policy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SpaceOperatingHoursPolicyTest {
    private lateinit var policy: SpaceOperatingHoursPolicy

    @BeforeEach
    fun setUp() {
        policy = SpaceOperatingHoursPolicy()
    }

    @Test
    @DisplayName("09:00부터 12:00까지 30분 단위 슬롯을 정상적으로 생성한다 (총 6개)")
    fun generateSlots_success() {
        // given
        val date = LocalDate.of(2026, 9, 20)
        val openingTime = LocalTime.of(9, 0)
        val closingTime = LocalTime.of(12, 0)

        // when
        val slots = policy.generateSlots(date, openingTime, closingTime)

        // then
        assertThat(slots).hasSize(6)
        assertThat(slots[0].start).isEqualTo(LocalDateTime.of(2026, 9, 20, 9, 0))
        assertThat(slots[0].end).isEqualTo(LocalDateTime.of(2026, 9, 20, 9, 30))
        assertThat(slots[5].start).isEqualTo(LocalDateTime.of(2026, 9, 20, 11, 30))
        assertThat(slots[5].end).isEqualTo(LocalDateTime.of(2026, 9, 20, 12, 0))

        // 자정 인근은 새 날짜로 넘기지 않고 같은 날짜 안에서만 생성한다.
        assertThat(policy.generateSlots(date, LocalTime.of(23, 0), LocalTime.of(23, 30)))
            .containsExactly(
                SpaceOperatingHoursPolicy.SlotWindow(
                    LocalDateTime.of(2026, 9, 20, 23, 0),
                    LocalDateTime.of(2026, 9, 20, 23, 30),
                ),
            )
        assertThat(policy.generateSlots(date, LocalTime.of(23, 30), LocalTime.of(0, 0))).isEmpty()
        assertThat(policy.generateSlots(date, LocalTime.of(0, 0), LocalTime.of(0, 0))).isEmpty()

        // 유효한 운영시간이면 검증 후 날짜를 사용하므로 date=null의 기존 NPE 시점이 유지된다.
        assertThatThrownBy {
            policy.generateSlots(null, LocalTime.of(9, 0), LocalTime.of(10, 0))
        }.isInstanceOf(NullPointerException::class.java)
    }

    @Test
    @DisplayName("시작 시각이 종료 시각과 같거나 늦거나 null이면 빈 목록을 반환한다")
    fun generateSlots_invalidHours_returnsEmptyList() {
        // given
        val date = LocalDate.of(2026, 9, 20)

        // when & then
        assertThat(policy.generateSlots(date, LocalTime.of(18, 0), LocalTime.of(9, 0))).isEmpty()
        assertThat(policy.generateSlots(date, LocalTime.of(9, 0), LocalTime.of(9, 0))).isEmpty()
        assertThat(policy.generateSlots(date, null, LocalTime.of(18, 0))).isEmpty()
        assertThat(policy.generateSlots(date, LocalTime.of(9, 0), null)).isEmpty()

        // 잘못된 운영시간은 date를 사용하기 전에 빈 목록으로 조기 반환한다.
        assertThat(policy.generateSlots(null, LocalTime.of(18, 0), LocalTime.of(9, 0))).isEmpty()
        assertThat(policy.generateSlots(null, null, LocalTime.of(18, 0))).isEmpty()
    }

    @Test
    @DisplayName("30분 한 칸 운영시간은 슬롯 1개를 생성한다")
    fun generateSlots_singleSlot() {
        val date = LocalDate.of(2026, 9, 20)

        val slots =
            policy.generateSlots(
                date,
                LocalTime.of(9, 0),
                LocalTime.of(9, 30),
            )

        assertThat(slots).containsExactly(
            SpaceOperatingHoursPolicy.SlotWindow(
                LocalDateTime.of(2026, 9, 20, 9, 0),
                LocalDateTime.of(2026, 9, 20, 9, 30),
            ),
        )
    }

    @Test
    @DisplayName("운영 시작 또는 종료 시각이 30분 경계가 아니면 빈 목록을 반환한다")
    fun generateSlots_notAlignedTo30Minutes_returnsEmptyList() {
        // given: 운영시간 자체가 30분 슬롯 격자와 맞지 않음
        val date = LocalDate.of(2026, 9, 20)
        val openingTime = LocalTime.of(9, 0)
        val closingTime = LocalTime.of(11, 45)

        // when
        val slots = policy.generateSlots(date, openingTime, closingTime)

        // then
        assertThat(slots).isEmpty()
        assertThat(policy.generateSlots(date, LocalTime.of(9, 15), LocalTime.of(12, 0))).isEmpty()
        assertThat(policy.generateSlots(date, LocalTime.of(9, 0, 1), LocalTime.of(12, 0))).isEmpty()
        assertThat(policy.generateSlots(date, LocalTime.of(9, 0, 0, 1), LocalTime.of(12, 0))).isEmpty()
    }

    @Test
    @DisplayName("운영시간 내 포함 여부를 올바르게 검증한다")
    fun isWithinOperatingHours() {
        val opening = LocalTime.of(9, 0)
        val closing = LocalTime.of(22, 0)

        // 정상 범위
        assertThat(policy.isWithinOperatingHours(opening, closing, LocalTime.of(9, 0), LocalTime.of(10, 0))).isTrue()
        assertThat(policy.isWithinOperatingHours(opening, closing, LocalTime.of(21, 30), LocalTime.of(22, 0))).isTrue()

        // 포함 판정은 슬롯 생성과 달리 30분 정렬을 요구하지 않는다.
        assertThat(policy.isWithinOperatingHours(opening, closing, LocalTime.of(9, 15), LocalTime.of(9, 45))).isTrue()

        // 운영시간 이전 또는 이후 침범
        assertThat(policy.isWithinOperatingHours(opening, closing, LocalTime.of(8, 30), LocalTime.of(9, 30))).isFalse()
        assertThat(policy.isWithinOperatingHours(opening, closing, LocalTime.of(21, 0), LocalTime.of(22, 30)))
            .isFalse()

        // 시작이 종료보다 늦은 경우
        assertThat(policy.isWithinOperatingHours(opening, closing, LocalTime.of(15, 0), LocalTime.of(14, 0))).isFalse()

        // 시작과 종료가 같은 경우
        assertThat(policy.isWithinOperatingHours(opening, closing, LocalTime.of(10, 0), LocalTime.of(10, 0))).isFalse()

        // 요청 시각이 null인 경우
        assertThat(policy.isWithinOperatingHours(opening, closing, null, LocalTime.of(10, 0))).isFalse()
        assertThat(policy.isWithinOperatingHours(opening, closing, LocalTime.of(9, 0), null)).isFalse()
        assertThat(policy.isWithinOperatingHours(null, closing, LocalTime.of(9, 0), LocalTime.of(10, 0))).isFalse()
        assertThat(policy.isWithinOperatingHours(opening, null, LocalTime.of(9, 0), LocalTime.of(10, 0))).isFalse()

        // 운영시간 자체가 유효하지 않은 경우
        assertThat(
            policy.isWithinOperatingHours(
                LocalTime.of(18, 0),
                LocalTime.of(9, 0),
                LocalTime.of(10, 0),
                LocalTime.of(11, 0),
            ),
        ).isFalse()
        assertThat(
            policy.isWithinOperatingHours(
                LocalTime.of(9, 0),
                LocalTime.of(9, 0),
                LocalTime.of(9, 0),
                LocalTime.of(10, 0),
            ),
        ).isFalse()
    }

    @Test
    @DisplayName("30분 단위 배수 여부를 정확히 판단한다")
    fun isMultipleOf30Minutes() {
        assertThat(policy.isMultipleOf30Minutes(LocalTime.of(9, 0))).isTrue()
        assertThat(policy.isMultipleOf30Minutes(LocalTime.of(9, 30))).isTrue()
        assertThat(policy.isMultipleOf30Minutes(LocalTime.of(0, 0))).isTrue()

        assertThat(policy.isMultipleOf30Minutes(LocalTime.of(9, 15))).isFalse()
        assertThat(policy.isMultipleOf30Minutes(LocalTime.of(9, 30, 1))).isFalse()
        assertThat(policy.isMultipleOf30Minutes(LocalTime.of(9, 0, 0, 500))).isFalse()
        assertThat(policy.isMultipleOf30Minutes(null)).isFalse()
    }
}
