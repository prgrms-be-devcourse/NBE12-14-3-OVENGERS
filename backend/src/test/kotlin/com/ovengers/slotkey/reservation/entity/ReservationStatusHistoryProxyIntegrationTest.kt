package com.ovengers.slotkey.reservation.entity

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.Hibernate
import org.hibernate.proxy.HibernateProxy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

@Transactional
@DisplayName("Step 4 예약 상태 이력 JPA 검증")
class ReservationStatusHistoryProxyIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var historyRepository: ReservationStatusHistoryRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    private val fixedAt = LocalDateTime.of(2026, 10, 25, 12, 0)

    @Test
    @DisplayName("프록시 getter 접근 시 상태 이력이 초기화된다")
    fun historyProxyInitializesOnGetterAccess() {
        val reservationId = createReservation()

        val saved =
            historyRepository.save(
                ReservationStatusHistory.of(
                    reservationId,
                    null,
                    ReservationStatus.HELD,
                    ReservationStatus.CONFIRMED,
                    "결제 완료",
                    fixedAt,
                ),
            )

        val historyId = requireNotNull(saved.id)

        entityManager.flush()
        entityManager.clear()

        val proxy =
            entityManager.getReference(
                ReservationStatusHistory::class.java,
                historyId,
            )

        assertThat(proxy).isInstanceOf(HibernateProxy::class.java)
        assertThat(Hibernate.isInitialized(proxy)).isFalse()

        val toStatus = proxy.toStatus

        assertThat(toStatus).isEqualTo(ReservationStatus.CONFIRMED)
        assertThat(Hibernate.isInitialized(proxy)).isTrue()

        assertThat(proxy.id).isEqualTo(historyId)
        assertThat(proxy.reservationId).isEqualTo(reservationId)
        assertThat(proxy.fromStatus).isEqualTo(ReservationStatus.HELD)
        assertThat(proxy.reason).isEqualTo("결제 완료")
        assertThat(proxy.changedAt).isEqualTo(fixedAt)
        assertThat(proxy.changedByMemberId).isNull()
    }

    @Test
    @DisplayName("변경 시각이 다른 상태 이력을 오름차순으로 조회한다")
    fun historiesAreOrderedByChangedAt() {
        val reservationId = createReservation()

        // 늦은 시각의 이력을 먼저 저장
        historyRepository.save(
            ReservationStatusHistory.of(
                reservationId,
                null,
                ReservationStatus.HELD,
                ReservationStatus.CONFIRMED,
                "결제 완료",
                fixedAt.plusMinutes(5),
            ),
        )

        // 이른 시각의 이력을 나중에 저장
        historyRepository.save(
            ReservationStatusHistory.of(
                reservationId,
                null,
                null,
                ReservationStatus.HELD,
                "예약 생성",
                fixedAt,
            ),
        )

        entityManager.flush()
        entityManager.clear()

        val histories =
            historyRepository.findAllByReservationIdOrderByChangedAtAsc(
                reservationId,
            )

        assertThat(histories).hasSize(2)
        assertThat(histories[0].toStatus).isEqualTo(ReservationStatus.HELD)
        assertThat(histories[0].changedAt).isEqualTo(fixedAt)
        assertThat(histories[1].toStatus).isEqualTo(ReservationStatus.CONFIRMED)
        assertThat(histories[1].changedAt).isEqualTo(fixedAt.plusMinutes(5))
    }

    private fun createReservation(): Long {
        val member =
            memberRepository.saveAndFlush(
                Member(
                    "history-${UUID.randomUUID()}@test.com",
                    "이력테스트",
                ),
            )

        val space =
            spaceRepository.saveAndFlush(
                Space
                    .builder()
                    .name("프록시 테스트 공간")
                    .location("강남")
                    .capacity(4)
                    .pricePerSlot(3000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(22, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )

        val reservation =
            reservationRepository.saveAndFlush(
                Reservation
                    .builder()
                    .memberId(requireNotNull(member.id))
                    .spaceId(requireNotNull(space.id))
                    .startTime(fixedAt.plusDays(1))
                    .endTime(fixedAt.plusDays(1).plusHours(1))
                    .status(ReservationStatus.CONFIRMED)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .createdAt(fixedAt.minusDays(1))
                    .build(),
            )

        return reservation.id
    }
}
