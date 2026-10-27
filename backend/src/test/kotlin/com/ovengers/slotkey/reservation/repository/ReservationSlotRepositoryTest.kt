package com.ovengers.slotkey.reservation.repository

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.adapter.ReservationOccupiedSlotAdapter
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationSlot
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.scheduler.ReservationBatchProcessor
import com.ovengers.slotkey.reservation.service.ReservationSlotService
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.FixedClockConfig
import com.ovengers.slotkey.support.IntegrationTestSupport
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.ArrayList

@Import(FixedClockConfig::class)
class ReservationSlotRepositoryTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var reservationSlotRepository: ReservationSlotRepository

    @Autowired
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    @Autowired
    private lateinit var reservationBatchProcessor: ReservationBatchProcessor

    @Autowired
    private lateinit var reservationSlotService: ReservationSlotService

    @Autowired
    private lateinit var reservationOccupiedSlotAdapter: ReservationOccupiedSlotAdapter

    @Autowired
    private lateinit var entityManager: EntityManager

    private var spaceId: Long = 0L
    private var memberId: Long = 0L

    @BeforeEach
    fun setUp() {
        reservationStatusHistoryRepository.deleteAllInBatch()
        reservationSlotRepository.deleteAllInBatch()
        reservationRepository.deleteAllInBatch()
        spaceRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()

        val space: Space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("테스트 회의실")
                    .location("서울")
                    .capacity(6)
                    .pricePerSlot(5000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(22, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )
        spaceId = space.id!!

        val member: Member =
            memberRepository.save(
                Member(
                    "repo-test-" + System.nanoTime() + "@slotkey.test",
                    "레포테스터",
                ),
            )
        memberId = member.id!!
    }

    @AfterEach
    fun tearDown() {
        reservationStatusHistoryRepository.deleteAllInBatch()
        reservationSlotRepository.deleteAllInBatch()
        reservationRepository.deleteAllInBatch()
        spaceRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
    }

    private fun createReservation(
        status: ReservationStatus,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        holdExpiresAt: LocalDateTime?,
    ): Reservation {
        val checkedInAt: LocalDateTime? =
            if (status == ReservationStatus.IN_USE ||
                status == ReservationStatus.COMPLETED
            ) {
                startTime
            } else {
                null
            }
        val checkedOutAt: LocalDateTime? = if (status == ReservationStatus.COMPLETED) endTime else null
        val cancelledAt: LocalDateTime? = if (status == ReservationStatus.CANCELLED) startTime.minusMinutes(30) else null
        val effectiveHoldExpiresAt: LocalDateTime? =
            if (status ==
                ReservationStatus.HELD
            ) {
                (holdExpiresAt ?: startTime.minusMinutes(10))
            } else {
                holdExpiresAt
            }

        return reservationRepository.save(
            Reservation
                .builder()
                .memberId(memberId)
                .spaceId(spaceId)
                .startTime(startTime)
                .endTime(endTime)
                .status(status)
                .pricePerSlotSnapshot(5000)
                .totalAmount(5000)
                .holdExpiresAt(effectiveHoldExpiresAt)
                .checkedInAt(checkedInAt)
                .checkedOutAt(checkedOutAt)
                .cancelledAt(cancelledAt)
                .createdAt(startTime.minusMinutes(10))
                .build(),
        )
    }

    private fun createSlot(
        reservationId: Long,
        slotStart: LocalDateTime,
    ): ReservationSlot = reservationSlotRepository.save(ReservationSlot.of(reservationId, spaceId, slotStart))

    @Test
    @DisplayName("유효한 HELD 슬롯은 점유 목록에 포함되고, 만료 시각(now)이 지난 HELD 슬롯은 점유 목록에서 제외된다")
    fun findOccupiedSlotStarts_heldSlotExpirationCheck() {
        // given
        val now: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)

        // 1. 유효한 HOLD: 만료 시각이 10:05 (now < holdExpiresAt) -> 점유
        val validHeld: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 11, 0),
                LocalDateTime.of(2026, 9, 20, 11, 30),
                LocalDateTime.of(2026, 9, 20, 10, 5, 0),
            )
        createSlot(validHeld.getId(), LocalDateTime.of(2026, 9, 20, 11, 0))

        // 2. 정확히 만료된 HOLD: 만료 시각이 10:00 (holdExpiresAt == now) -> 비점유
        val exactExpiredHeld: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 11, 30),
                LocalDateTime.of(2026, 9, 20, 12, 0),
                LocalDateTime.of(2026, 9, 20, 10, 0, 0),
            )
        createSlot(exactExpiredHeld.getId(), LocalDateTime.of(2026, 9, 20, 11, 30))

        // 3. 이미 만료된 HOLD: 만료 시각이 09:55 (holdExpiresAt < now) -> 비점유
        val pastExpiredHeld: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 12, 0),
                LocalDateTime.of(2026, 9, 20, 12, 30),
                LocalDateTime.of(2026, 9, 20, 9, 55, 0),
            )
        createSlot(pastExpiredHeld.getId(), LocalDateTime.of(2026, 9, 20, 12, 0))

        reservationSlotRepository.flush()
        entityManager.clear()

        val startOfDay: LocalDateTime = LocalDateTime.of(2026, 9, 20, 0, 0, 0)
        val nextDayStart: LocalDateTime = LocalDateTime.of(2026, 9, 21, 0, 0, 0)

        // when
        val occupiedSlots: List<LocalDateTime> =
            reservationSlotRepository.findOccupiedSlotStarts(
                spaceId,
                startOfDay,
                nextDayStart,
                now,
                ReservationStatus.HELD,
                OCCUPIED_STATUSES,
            )

        // then
        assertThat(occupiedSlots).containsExactly(LocalDateTime.of(2026, 9, 20, 11, 0))
    }

    @Test
    @DisplayName("CONFIRMED, IN_USE, COMPLETED는 점유로 포함되고 EXPIRED, CANCELLED, NO_SHOW는 점유에서 제외된다")
    fun findOccupiedSlotStarts_statusFiltering() {
        // given
        val now: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)

        val confirmed: Reservation =
            createReservation(
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 9, 20, 11, 0),
                LocalDateTime.of(2026, 9, 20, 11, 30),
                null,
            )
        createSlot(confirmed.getId(), LocalDateTime.of(2026, 9, 20, 11, 0))

        val inUse: Reservation =
            createReservation(
                ReservationStatus.IN_USE,
                LocalDateTime.of(2026, 9, 20, 11, 30),
                LocalDateTime.of(2026, 9, 20, 12, 0),
                null,
            )
        createSlot(inUse.getId(), LocalDateTime.of(2026, 9, 20, 11, 30))

        val completed: Reservation =
            createReservation(
                ReservationStatus.COMPLETED,
                LocalDateTime.of(2026, 9, 20, 12, 0),
                LocalDateTime.of(2026, 9, 20, 12, 30),
                null,
            )
        createSlot(completed.getId(), LocalDateTime.of(2026, 9, 20, 12, 0))

        val expired: Reservation =
            createReservation(
                ReservationStatus.EXPIRED,
                LocalDateTime.of(2026, 9, 20, 12, 30),
                LocalDateTime.of(2026, 9, 20, 13, 0),
                null,
            )
        createSlot(expired.getId(), LocalDateTime.of(2026, 9, 20, 12, 30))

        val cancelled: Reservation =
            createReservation(
                ReservationStatus.CANCELLED,
                LocalDateTime.of(2026, 9, 20, 13, 0),
                LocalDateTime.of(2026, 9, 20, 13, 30),
                null,
            )
        createSlot(cancelled.getId(), LocalDateTime.of(2026, 9, 20, 13, 0))

        val noShow: Reservation =
            createReservation(
                ReservationStatus.NO_SHOW,
                LocalDateTime.of(2026, 9, 20, 13, 30),
                LocalDateTime.of(2026, 9, 20, 14, 0),
                null,
            )
        createSlot(noShow.getId(), LocalDateTime.of(2026, 9, 20, 13, 30))

        val startOfDay: LocalDateTime = LocalDateTime.of(2026, 9, 20, 0, 0, 0)
        val nextDayStart: LocalDateTime = LocalDateTime.of(2026, 9, 21, 0, 0, 0)

        // when
        val occupiedSlots: List<LocalDateTime> =
            reservationSlotRepository.findOccupiedSlotStarts(
                spaceId,
                startOfDay,
                nextDayStart,
                now,
                ReservationStatus.HELD,
                OCCUPIED_STATUSES,
            )

        // then
        assertThat(occupiedSlots).containsExactlyInAnyOrder(
            LocalDateTime.of(2026, 9, 20, 11, 0),
            LocalDateTime.of(2026, 9, 20, 11, 30),
            LocalDateTime.of(2026, 9, 20, 12, 0),
        )
    }

    @Test
    @DisplayName("반개구간 조회로 당일 23:30 슬롯은 포함되고 익일 00:00 슬롯은 제외된다")
    fun findOccupiedSlotStarts_halfOpenInterval() {
        // given
        val now: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)

        val slot2330: Reservation =
            createReservation(
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 9, 20, 23, 30),
                LocalDateTime.of(2026, 9, 21, 0, 0),
                null,
            )
        createSlot(slot2330.getId(), LocalDateTime.of(2026, 9, 20, 23, 30))

        val slotNextDay0000: Reservation =
            createReservation(
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 9, 21, 0, 0),
                LocalDateTime.of(2026, 9, 21, 0, 30),
                null,
            )
        createSlot(slotNextDay0000.getId(), LocalDateTime.of(2026, 9, 21, 0, 0))

        val startOfDay: LocalDateTime = LocalDateTime.of(2026, 9, 20, 0, 0, 0)
        val nextDayStart: LocalDateTime = LocalDateTime.of(2026, 9, 21, 0, 0, 0)

        // when
        val occupiedSlots: List<LocalDateTime> =
            reservationSlotRepository.findOccupiedSlotStarts(
                spaceId,
                startOfDay,
                nextDayStart,
                now,
                ReservationStatus.HELD,
                OCCUPIED_STATUSES,
            )

        // then
        assertThat(occupiedSlots).containsExactly(LocalDateTime.of(2026, 9, 20, 23, 30))
    }

    @Test
    @Transactional
    @DisplayName("holdExpiresAt == now 일 때 findExpiredHoldIds 및 expireHeldReservations, deleteSlotsOfExpiredReservations가 정상 동작한다")
    fun expireHeldReservations_exactNowMatch() {
        // given
        val now: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)

        val exactHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 11, 0),
                LocalDateTime.of(2026, 9, 20, 11, 30),
                now,
            ) // holdExpiresAt == now
        createSlot(exactHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 0))

        val futureHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 11, 30),
                LocalDateTime.of(2026, 9, 20, 12, 0),
                now.plusSeconds(1),
            ) // holdExpiresAt > now
        createSlot(futureHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 30))

        // when
        val expiredHoldIds: List<Long> = reservationRepository.findExpiredHoldIds(now, ReservationStatus.HELD)

        // then
        assertThat(expiredHoldIds).containsExactly(exactHold.getId())

        // and when
        val updated: Int =
            reservationRepository.expireHeldReservations(
                listOf(exactHold.getId(), futureHold.getId()),
                now,
                ReservationStatus.HELD,
                ReservationStatus.EXPIRED,
            )

        // then
        assertThat(updated).isEqualTo(1)
        val reloadedExact: Reservation = reservationRepository.findById(exactHold.getId()).orElseThrow()
        val reloadedFuture: Reservation = reservationRepository.findById(futureHold.getId()).orElseThrow()
        assertThat(reloadedExact.getStatus()).isEqualTo(ReservationStatus.EXPIRED)
        assertThat(reloadedFuture.getStatus()).isEqualTo(ReservationStatus.HELD)

        // and when: 만료된 예약의 슬롯 삭제 수행
        val deletedSlots: Int =
            reservationSlotRepository.deleteSlotsOfExpiredReservations(
                listOf(exactHold.getId(), futureHold.getId()),
                ReservationStatus.EXPIRED,
            )

        // then: exactHold의 슬롯만 삭제되고 futureHold의 슬롯은 유지됨
        assertThat(deletedSlots).isEqualTo(1)
        assertThat(reservationSlotRepository.findAllByReservationId(exactHold.getId())).isEmpty()
        assertThat(reservationSlotRepository.findAllByReservationId(futureHold.getId())).hasSize(1)
    }

    @Test
    @DisplayName("ReservationBatchProcessor.expireHold는 holdExpiresAt == now일 때 EXPIRED 전이, 슬롯 삭제, 이력 저장을 실제 DB에서 수행한다")
    fun expireHold_exactNow_deletesSlotsAndSavesHistoryInRealDb() {
        // given
        val now: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)

        val exactHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 11, 0),
                LocalDateTime.of(2026, 9, 20, 11, 30),
                now,
            ) // holdExpiresAt == now
        createSlot(exactHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 0))

        // when (테스트 트랜잭션 없이 실제 트랜잭션 커밋 수행)
        val expired: Boolean = reservationBatchProcessor.expireHold(exactHold.getId(), now)

        // then
        assertThat(expired).isTrue()

        val reloaded: Reservation = reservationRepository.findById(exactHold.getId()).orElseThrow()
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.EXPIRED)

        val remainingSlots: List<ReservationSlot> =
            reservationSlotRepository
                .findAllByReservationId(exactHold.getId())
        assertThat(remainingSlots).isEmpty()

        val histories: List<ReservationStatusHistory> =
            reservationStatusHistoryRepository
                .findAllByReservationIdOrderByChangedAtAsc(exactHold.getId())
        assertThat(histories).hasSize(1)
        val history: ReservationStatusHistory = histories.get(0)
        assertThat(history.fromStatus).isEqualTo(ReservationStatus.HELD)
        assertThat(history.toStatus).isEqualTo(ReservationStatus.EXPIRED)
        assertThat(history.reason).isEqualTo("HOLD_EXPIRED")
        assertThat(history.changedAt).isEqualTo(now)
    }

    @Test
    @DisplayName("ReservationSlotService.secureSlots는 holdExpiresAt == now인 기존 HOLD를 정리하고 새 예약 슬롯을 성공적으로 확보한다")
    fun secureSlots_exactExpiredHold_cleansUpAndSecuresNewSlotInRealDb() {
        // given (FixedClockConfig의 고정 시각 = 2026-09-17 12:00:00)
        val now: LocalDateTime = FixedClockConfig.FIXED_DATE_TIME
        val slotStart: LocalDateTime = now.plusHours(2) // 14:00:00
        val slotEnd: LocalDateTime = slotStart.plusMinutes(30)

        // 기존 HOLD: 만료 시각이 정확히 now
        val oldHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                slotStart,
                slotEnd,
                now,
            )
        createSlot(oldHold.getId(), slotStart)

        // 신규 예약: 동일 슬롯 요청
        val newHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                slotStart,
                slotEnd,
                now.plusMinutes(10),
            )

        // when (테스트 트랜잭션 없이 실제 secureSlots 트랜잭션 실행)
        reservationSlotService.secureSlots(newHold.getId(), spaceId, listOf(slotStart))

        // then
        val reloadedOld: Reservation = reservationRepository.findById(oldHold.getId()).orElseThrow()
        assertThat(reloadedOld.getStatus()).isEqualTo(ReservationStatus.EXPIRED)
        assertThat(reservationSlotRepository.findAllByReservationId(oldHold.getId())).isEmpty()

        val oldHistories: List<ReservationStatusHistory> =
            reservationStatusHistoryRepository
                .findAllByReservationIdOrderByChangedAtAsc(oldHold.getId())
        assertThat(oldHistories).hasSize(1)
        val history: ReservationStatusHistory = oldHistories.get(0)
        assertThat(history.fromStatus).isEqualTo(ReservationStatus.HELD)
        assertThat(history.toStatus).isEqualTo(ReservationStatus.EXPIRED)
        assertThat(history.reason).isEqualTo("HOLD_EXPIRED")
        assertThat(history.changedByMemberId).isNull()
        assertThat(history.changedAt).isEqualTo(now)

        val newSlots: List<ReservationSlot> = reservationSlotRepository.findAllByReservationId(newHold.getId())
        assertThat(newSlots).hasSize(1)
        assertThat(newSlots.get(0).slotStart).isEqualTo(slotStart)
        assertThat(newSlots.get(0).reservationId).isEqualTo(newHold.getId())
    }

    @Test
    @DisplayName("이미 만료된 예약에 대해 expireHold를 다시 시도하면 false를 반환하고 이력이 중복 추가되지 않는다")
    fun expireHold_alreadyExpired_returnsFalseAndDoesNotAddDuplicateHistory() {
        // given
        val now: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)

        val exactHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 11, 0),
                LocalDateTime.of(2026, 9, 20, 11, 30),
                now,
            )
        createSlot(exactHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 0))

        val firstAttempt: Boolean = reservationBatchProcessor.expireHold(exactHold.getId(), now)
        assertThat(firstAttempt).isTrue()
        assertThat(
            reservationStatusHistoryRepository
                .findAllByReservationIdOrderByChangedAtAsc(exactHold.getId()),
        ).hasSize(1)

        // when
        val secondAttempt: Boolean = reservationBatchProcessor.expireHold(exactHold.getId(), now)

        // then
        assertThat(secondAttempt).isFalse()
        val histories: List<ReservationStatusHistory> =
            reservationStatusHistoryRepository
                .findAllByReservationIdOrderByChangedAtAsc(exactHold.getId())
        assertThat(histories).hasSize(1)
    }

    @Test
    @DisplayName("holdExpiresAt == now + 1초인 미래 HOLD는 만료되지 않고 상태와 슬롯이 유지되며 이력이 생성되지 않는다")
    fun expireHold_futureHold_keepsStatusAndSlotsAndCreatesNoHistory() {
        // given
        val now: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)

        val futureHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 11, 0),
                LocalDateTime.of(2026, 9, 20, 11, 30),
                now.plusSeconds(1),
            )
        createSlot(futureHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 0))

        // when
        val result: Boolean = reservationBatchProcessor.expireHold(futureHold.getId(), now)

        // then
        assertThat(result).isFalse()

        val reloaded: Reservation = reservationRepository.findById(futureHold.getId()).orElseThrow()
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.HELD)

        val slots: List<ReservationSlot> = reservationSlotRepository.findAllByReservationId(futureHold.getId())
        assertThat(slots).hasSize(1)

        val histories: List<ReservationStatusHistory> =
            reservationStatusHistoryRepository
                .findAllByReservationIdOrderByChangedAtAsc(futureHold.getId())
        assertThat(histories).isEmpty()
    }

    @Test
    @DisplayName(
        "findOccupiedSlotStartsEndingAfter는 포함 상태(CONFIRMED, IN_USE, COMPLETED, 유효 HELD)만 조회하고 제외 상태(EXPIRED, CANCELLED, NO_SHOW, 만료 HELD)는 제외한다",
    )
    fun findOccupiedSlotStartsEndingAfter_statusFilter_includesOnlyValidOccupied() {
        // given
        val now: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)
        val slotStartAfter: LocalDateTime = now.minusMinutes(30)

        // 포함 대상 4종
        val confirmed: Reservation =
            createReservation(
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 9, 20, 11, 0),
                LocalDateTime.of(2026, 9, 20, 11, 30),
                null,
            )
        createSlot(confirmed.getId(), LocalDateTime.of(2026, 9, 20, 11, 0))

        val inUse: Reservation =
            createReservation(
                ReservationStatus.IN_USE,
                LocalDateTime.of(2026, 9, 20, 11, 30),
                LocalDateTime.of(2026, 9, 20, 12, 0),
                null,
            )
        createSlot(inUse.getId(), LocalDateTime.of(2026, 9, 20, 11, 30))

        val completed: Reservation =
            createReservation(
                ReservationStatus.COMPLETED,
                LocalDateTime.of(2026, 9, 20, 12, 0),
                LocalDateTime.of(2026, 9, 20, 12, 30),
                null,
            )
        createSlot(completed.getId(), LocalDateTime.of(2026, 9, 20, 12, 0))

        val validHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 12, 30),
                LocalDateTime.of(2026, 9, 20, 13, 0),
                now.plusSeconds(1),
            )
        createSlot(validHold.getId(), LocalDateTime.of(2026, 9, 20, 12, 30))

        // 제외 대상 4종
        val expired: Reservation =
            createReservation(
                ReservationStatus.EXPIRED,
                LocalDateTime.of(2026, 9, 20, 13, 0),
                LocalDateTime.of(2026, 9, 20, 13, 30),
                null,
            )
        createSlot(expired.getId(), LocalDateTime.of(2026, 9, 20, 13, 0))

        val cancelled: Reservation =
            createReservation(
                ReservationStatus.CANCELLED,
                LocalDateTime.of(2026, 9, 20, 13, 30),
                LocalDateTime.of(2026, 9, 20, 14, 0),
                null,
            )
        createSlot(cancelled.getId(), LocalDateTime.of(2026, 9, 20, 13, 30))

        val noShow: Reservation =
            createReservation(
                ReservationStatus.NO_SHOW,
                LocalDateTime.of(2026, 9, 20, 14, 0),
                LocalDateTime.of(2026, 9, 20, 14, 30),
                null,
            )
        createSlot(noShow.getId(), LocalDateTime.of(2026, 9, 20, 14, 0))

        val expiredHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 14, 30),
                LocalDateTime.of(2026, 9, 20, 15, 0),
                now,
            )
        createSlot(expiredHold.getId(), LocalDateTime.of(2026, 9, 20, 14, 30))

        // when
        val result: List<LocalDateTime> =
            reservationSlotRepository.findOccupiedSlotStartsEndingAfter(
                spaceId,
                slotStartAfter,
                now,
                ReservationStatus.HELD,
                OCCUPIED_STATUSES,
            )

        // then
        assertThat(result).containsExactlyInAnyOrder(
            LocalDateTime.of(2026, 9, 20, 11, 0),
            LocalDateTime.of(2026, 9, 20, 11, 30),
            LocalDateTime.of(2026, 9, 20, 12, 0),
            LocalDateTime.of(2026, 9, 20, 12, 30),
        )
    }

    @Test
    @DisplayName("findOccupiedSlotStartsEndingAfter의 HELD 만료 경계: holdExpiresAt == now는 제외되고 now + 1초는 포함되며 now - 1초는 제외된다")
    fun findOccupiedSlotStartsEndingAfter_heldExpiryBoundary() {
        // given
        val now: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)
        val slotStartAfter: LocalDateTime = now.minusMinutes(30)

        // 1. now - 1초 (이미 만료)
        val pastHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 11, 0),
                LocalDateTime.of(2026, 9, 20, 11, 30),
                now.minusSeconds(1),
            )
        createSlot(pastHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 0))

        // 2. 정확히 now (만료 경계: now < holdExpiresAt 불만족)
        val exactHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 11, 30),
                LocalDateTime.of(2026, 9, 20, 12, 0),
                now,
            )
        createSlot(exactHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 30))

        // 3. now + 1초 (유효)
        val futureHold: Reservation =
            createReservation(
                ReservationStatus.HELD,
                LocalDateTime.of(2026, 9, 20, 12, 0),
                LocalDateTime.of(2026, 9, 20, 12, 30),
                now.plusSeconds(1),
            )
        createSlot(futureHold.getId(), LocalDateTime.of(2026, 9, 20, 12, 0))

        // when
        val result: List<LocalDateTime> =
            reservationSlotRepository.findOccupiedSlotStartsEndingAfter(
                spaceId,
                slotStartAfter,
                now,
                ReservationStatus.HELD,
                OCCUPIED_STATUSES,
            )

        // then
        assertThat(result).containsExactly(LocalDateTime.of(2026, 9, 20, 12, 0))
    }

    @Test
    @DisplayName("findOccupiedSlotStartsEndingAfter의 시간 구간 경계: slotStart < now < slotEnd(진행 중), slotStart == now, slotEnd == now 검증")
    fun findOccupiedSlotStartsEndingAfter_timeSlotBoundaries() {
        // given
        val now: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 15, 0)
        val slotStartAfter: LocalDateTime = now.minusMinutes(30) // 09:45

        // 1. 이미 완전히 끝난 슬롯: 09:00 ~ 09:30 (slotStart 09:00 <= 09:45) -> 제외
        val endedBefore: Reservation =
            createReservation(
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 9, 20, 9, 0),
                LocalDateTime.of(2026, 9, 20, 9, 30),
                null,
            )
        createSlot(endedBefore.getId(), LocalDateTime.of(2026, 9, 20, 9, 0))

        // 2. slotEnd == now 경계: now가 10:00일 때 09:30 ~ 10:00 슬롯
        val nowAtBoundary: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)
        val slotStartAfterAtBoundary: LocalDateTime = nowAtBoundary.minusMinutes(30) // 09:30
        val endedJustNow: Reservation =
            createReservation(
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 9, 20, 9, 30),
                LocalDateTime.of(2026, 9, 20, 10, 0),
                null,
            )
        createSlot(endedJustNow.getId(), LocalDateTime.of(2026, 9, 20, 9, 30))

        // slotEnd == now인 경우 slotStart (09:30) > slotStartAfter (09:30) 조건에 의해 엄격하게 제외됨
        val boundaryCheck: List<LocalDateTime> =
            reservationSlotRepository.findOccupiedSlotStartsEndingAfter(
                spaceId,
                slotStartAfterAtBoundary,
                nowAtBoundary,
                ReservationStatus.HELD,
                OCCUPIED_STATUSES,
            )
        assertThat(boundaryCheck).doesNotContain(LocalDateTime.of(2026, 9, 20, 9, 30))

        // 3. slotStart < now < slotEnd (진행 중 슬롯): 10:00 ~ 10:30 (now=10:15)
        // slotStart (10:00) > slotStartAfter (09:45) -> 포함되어야 함!
        val running: Reservation =
            createReservation(
                ReservationStatus.IN_USE,
                LocalDateTime.of(2026, 9, 20, 10, 0),
                LocalDateTime.of(2026, 9, 20, 10, 30),
                null,
            )
        createSlot(running.getId(), LocalDateTime.of(2026, 9, 20, 10, 0))

        // 4. slotStart == now 경계: now가 10:30일 때 10:30 ~ 11:00 슬롯
        val nowExact: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 30, 0)
        val slotStartAfterExact: LocalDateTime = nowExact.minusMinutes(30) // 10:00
        val startingJustNow: Reservation =
            createReservation(
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 9, 20, 10, 30),
                LocalDateTime.of(2026, 9, 20, 11, 0),
                null,
            )
        createSlot(startingJustNow.getId(), LocalDateTime.of(2026, 9, 20, 10, 30))

        // when (1): now=10:15 기준 조회
        val resultAt1015: List<LocalDateTime> =
            reservationSlotRepository.findOccupiedSlotStartsEndingAfter(
                spaceId,
                slotStartAfter,
                now,
                ReservationStatus.HELD,
                OCCUPIED_STATUSES,
            )

        // then (1): now=10:15 시점에 진행 중인 10:00 슬롯과 이후 10:30 슬롯이 정상 포함됨
        assertThat(resultAt1015).contains(
            LocalDateTime.of(2026, 9, 20, 10, 0),
            LocalDateTime.of(2026, 9, 20, 10, 30),
        )
        assertThat(resultAt1015).doesNotContain(
            LocalDateTime.of(2026, 9, 20, 9, 0),
            LocalDateTime.of(2026, 9, 20, 9, 30),
        )

        // when (2): nowExact=10:30 기준 조회 (slotStart == nowExact 경계값 검증)
        val resultAt1030: List<LocalDateTime> =
            reservationSlotRepository.findOccupiedSlotStartsEndingAfter(
                spaceId,
                slotStartAfterExact,
                nowExact,
                ReservationStatus.HELD,
                OCCUPIED_STATUSES,
            )

        // then (2): slotStart == nowExact인 10:30 슬롯은 포함되고, 10:00 슬롯은 slotEnd(10:30) ==
        // nowExact이므로 제외됨
        assertThat(resultAt1030).contains(LocalDateTime.of(2026, 9, 20, 10, 30))
        assertThat(resultAt1030).doesNotContain(
            LocalDateTime.of(2026, 9, 20, 9, 0),
            LocalDateTime.of(2026, 9, 20, 9, 30),
            LocalDateTime.of(2026, 9, 20, 10, 0),
        )
    }

    @Test
    @DisplayName("격리 통합: 점유 포트(유효/만료 HOLD)와 공간 저장소 영업시간 조건 결합 시 유효 HOLD 및 영업시간 밖 공간 제외, 만료 HOLD 공간 포함 검증")
    fun searchSpaces_combinedWithOccupiedSlotProviderAndOperatingHours() {
        // given: 공간 3개 생성
        // 1. 유효 HOLD 공간: 09:00~18:00 영업 (검색 시간 10:00~12:00 포함), 활성
        val validHoldSpace: Space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("유효홀드공간")
                    .location("성남시 분당구")
                    .description("유효한 HOLD가 걸려 있는 공간")
                    .capacity(4)
                    .pricePerSlot(5000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(18, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )

        // 2. 영업시간 밖 비점유 공간: 14:00~22:00 영업 (검색 시간 10:00~12:00 불포함), 예약 없음
        val outsideHoursSpace: Space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("영업시간밖공간")
                    .location("성남시 분당구")
                    .description("검색 시간대 영업하지 않는 비점유 공간")
                    .capacity(6)
                    .pricePerSlot(6000L)
                    .openingTime(LocalTime.of(14, 0))
                    .closingTime(LocalTime.of(22, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )

        // 3. 만료 HOLD 공간: 09:00~18:00 영업 (검색 시간 10:00~12:00 포함), 활성
        val expiredHoldSpace: Space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("만료홀드공간")
                    .location("성남시 분당구")
                    .description("만료된 HOLD만 있어 점유 해제된 공간")
                    .capacity(4)
                    .pricePerSlot(7000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(18, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )

        val now: LocalDateTime = LocalDateTime.of(2026, 10, 1, 9, 30)

        // validHoldSpace: 유효한 HOLD 예약 생성 (holdExpiresAt = 09:40 > now)
        val validHoldReservation: Reservation =
            reservationRepository.save(
                Reservation
                    .builder()
                    .memberId(memberId)
                    .spaceId(validHoldSpace.id!!)
                    .startTime(LocalDateTime.of(2026, 10, 1, 10, 0))
                    .endTime(LocalDateTime.of(2026, 10, 1, 10, 30))
                    .status(ReservationStatus.HELD)
                    .pricePerSlotSnapshot(5000)
                    .totalAmount(5000)
                    .holdExpiresAt(now.plusMinutes(10))
                    .createdAt(now.minusMinutes(10))
                    .build(),
            )
        reservationSlotRepository.save(
            ReservationSlot.of(
                validHoldReservation.getId(),
                validHoldSpace.id!!,
                LocalDateTime.of(2026, 10, 1, 10, 0),
            ),
        )

        // expiredHoldSpace: 만료된 HOLD 예약 생성 (holdExpiresAt = 09:25 < now)
        val expiredHoldReservation: Reservation =
            reservationRepository.save(
                Reservation
                    .builder()
                    .memberId(memberId)
                    .spaceId(expiredHoldSpace.id!!)
                    .startTime(LocalDateTime.of(2026, 10, 1, 10, 0))
                    .endTime(LocalDateTime.of(2026, 10, 1, 10, 30))
                    .status(ReservationStatus.HELD)
                    .pricePerSlotSnapshot(7000)
                    .totalAmount(7000)
                    .holdExpiresAt(now.minusMinutes(5))
                    .createdAt(now.minusMinutes(20))
                    .build(),
            )
        reservationSlotRepository.save(
            ReservationSlot.of(
                expiredHoldReservation.getId(),
                expiredHoldSpace.id!!,
                LocalDateTime.of(2026, 10, 1, 10, 0),
            ),
        )

        // when: 점유 포트 조회 (10:00 ~ 12:00, now=09:30)
        val startInclusive: LocalDateTime = LocalDateTime.of(2026, 10, 1, 10, 0)
        val endExclusive: LocalDateTime = LocalDateTime.of(2026, 10, 1, 12, 0)
        val occupiedSpaceIds: Set<Long> =
            reservationOccupiedSlotAdapter.getOccupiedSpaceIds(
                startInclusive,
                endExclusive,
                now,
            )

        // then (1): 점유 포트 결과 단언
        assertThat(occupiedSpaceIds).contains(validHoldSpace.id!!)
        assertThat(occupiedSpaceIds).doesNotContain(expiredHoldSpace.id!!)
        assertThat(occupiedSpaceIds).doesNotContain(outsideHoursSpace.id!!)

        // when (2): SpaceQueryService와 동일하게 점유 제외 ID와 영업시간 조건을 spaceRepository.searchSpaces에 전달
        val excludedIds: List<Long> = if (occupiedSpaceIds.isEmpty()) listOf(-1L) else ArrayList(occupiedSpaceIds)
        val searchResult: Page<Space> =
            spaceRepository.searchSpaces(
                SpaceStatus.ACTIVE,
                null,
                null,
                null,
                null,
                LocalTime.of(10, 0),
                LocalTime.of(12, 0),
                excludedIds,
                PageRequest.of(0, 10, Sort.by("id").ascending()),
            )

        // then (2): 최종 검색 결과 단언
        // - 영업시간 안 유효 HOLD 공간은 점유 포트에 의해 제외됨
        // - 영업시간 밖 비점유 공간은 영업시간 조건(WHERE openingTime <= 10:00 AND closingTime >= 12:00)에 의해 제외됨
        // - 영업시간 안 만료 HOLD 공간은 정상 포함됨
        val resultSpaceIds: List<Long> = searchResult.content.map { it.id!! }
        assertThat(resultSpaceIds).doesNotContain(validHoldSpace.id!!)
        assertThat(resultSpaceIds).doesNotContain(outsideHoursSpace.id!!)
        assertThat(resultSpaceIds).contains(expiredHoldSpace.id!!)
    }

    companion object {
        private val OCCUPIED_STATUSES: List<ReservationStatus> =
            listOf(
                ReservationStatus.CONFIRMED,
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED,
            )
    }
}
