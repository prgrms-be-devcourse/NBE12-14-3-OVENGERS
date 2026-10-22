package com.ovengers.slotkey.reservation.repository

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ReservationTargetInfoRepositoryTest : ReservationIntegrationTestSupport() {
    @Test
    @DisplayName("예약 잠금 전 JPQL 프로젝션은 공간과 소유자 식별자를 그대로 반환한다")
    fun findTargetInfoById_returnsSpaceAndOwner() {
        val memberId = createMember(10_000)
        val spaceId = createSpace()
        val start = tomorrowAt(14, 0)
        val reservationId = createReservation(memberId, spaceId, ReservationStatus.HELD, start, start.plusMinutes(30))

        val target = reservationRepository.findTargetInfoById(reservationId).orElseThrow()

        assertThat(target.spaceId).isEqualTo(spaceId)
        assertThat(target.memberId).isEqualTo(memberId)
    }
}
