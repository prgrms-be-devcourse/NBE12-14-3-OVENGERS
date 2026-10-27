
package com.ovengers.slotkey.reservation.repository

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.LocalTime

@Transactional
@DisplayName("회원별 전체 예약 건수 Repository 통합 테스트")
class ReservationMemberCountRepositoryTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Test
    @DisplayName("예약 상태와 관계없이 해당 회원의 예약만 집계한다")
    fun countByMemberId_includesAllStatusesAndExcludesOtherMembers() {
        // given
        val member: Member =
            memberRepository.save(
                Member("count-user@test.com", "조회대상"),
            )

        val otherMember: Member =
            memberRepository.save(
                Member("count-other@test.com", "다른회원"),
            )

        val space: Space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("테스트 회의실")
                    .location("서울")
                    .capacity(4)
                    .pricePerSlot(3000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(22, 0))
                    .status(SpaceStatus.ACTIVE)
                    .build(),
            )

        // 조회 대상 회원의 예약 3건
        saveReservation(
            member.id!!,
            space.id!!,
            ReservationStatus.HELD,
            10,
        )

        saveReservation(
            member.id!!,
            space.id!!,
            ReservationStatus.CONFIRMED,
            11,
        )

        saveReservation(
            member.id!!,
            space.id!!,
            ReservationStatus.CANCELLED,
            12,
        )

        // 다른 회원의 예약 1건
        saveReservation(
            otherMember.id!!,
            space.id!!,
            ReservationStatus.CONFIRMED,
            13,
        )

        // when
        val count: Long =
            reservationRepository.countByMemberId(
                member.id!!,
            )

        // then
        assertThat(count).isEqualTo(3L)
    }

    private fun saveReservation(
        memberId: Long,
        spaceId: Long,
        status: ReservationStatus,
        hour: Int,
    ) {
        val startTime: LocalDateTime = LocalDateTime.of(2026, 10, 20, hour, 0)

        val createdAt: LocalDateTime = startTime.minusDays(1)

        reservationRepository.save(
            Reservation
                .builder()
                .memberId(memberId)
                .spaceId(spaceId)
                .startTime(startTime)
                .endTime(startTime.plusMinutes(30))
                .status(status)
                .pricePerSlotSnapshot(3000)
                .totalAmount(3000)
                .createdAt(createdAt)
                .holdExpiresAt(
                    if (status == ReservationStatus.HELD) createdAt.plusMinutes(10) else null,
                ).cancelledAt(
                    if (status == ReservationStatus.CANCELLED) createdAt.plusMinutes(5) else null,
                ).build(),
        )
    }
}
