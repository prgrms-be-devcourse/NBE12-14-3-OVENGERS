package com.ovengers.slotkey.reservation.repository;

import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationSlot;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.reservation.scheduler.ReservationBatchProcessor;
import com.ovengers.slotkey.reservation.service.ReservationSlotService;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import com.ovengers.slotkey.support.FixedClockConfig;
import com.ovengers.slotkey.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import com.ovengers.slotkey.reservation.adapter.ReservationOccupiedSlotAdapter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(FixedClockConfig.class)
class ReservationSlotRepositoryTest extends IntegrationTestSupport {

        private static final List<ReservationStatus> OCCUPIED_STATUSES = List.of(
                        ReservationStatus.CONFIRMED,
                        ReservationStatus.IN_USE,
                        ReservationStatus.COMPLETED);

        @Autowired
        private MemberRepository memberRepository;

        @Autowired
        private SpaceRepository spaceRepository;

        @Autowired
        private ReservationRepository reservationRepository;

        @Autowired
        private ReservationSlotRepository reservationSlotRepository;

        @Autowired
        private ReservationStatusHistoryRepository reservationStatusHistoryRepository;

        @Autowired
        private ReservationBatchProcessor reservationBatchProcessor;

        @Autowired
        private ReservationSlotService reservationSlotService;

        @Autowired
        private ReservationOccupiedSlotAdapter reservationOccupiedSlotAdapter;

        @Autowired
        private EntityManager entityManager;

        private Long spaceId;
        private Long memberId;

        @BeforeEach
        void setUp() {
                reservationStatusHistoryRepository.deleteAllInBatch();
                reservationSlotRepository.deleteAllInBatch();
                reservationRepository.deleteAllInBatch();
                spaceRepository.deleteAllInBatch();
                memberRepository.deleteAllInBatch();

                Space space = spaceRepository.save(Space.builder()
                                .name("테스트 회의실")
                                .location("서울")
                                .capacity(6)
                                .pricePerSlot(5000L)
                                .openingTime(LocalTime.of(9, 0))
                                .closingTime(LocalTime.of(22, 0))
                                .status(SpaceStatus.ACTIVE)
                                .version(0)
                                .build());
                spaceId = space.getId();

                Member member = memberRepository.save(new Member(
                                "repo-test-" + System.nanoTime() + "@slotkey.test",
                                "레포테스터"));
                memberId = member.getId();
        }

        @AfterEach
        void tearDown() {
                reservationStatusHistoryRepository.deleteAllInBatch();
                reservationSlotRepository.deleteAllInBatch();
                reservationRepository.deleteAllInBatch();
                spaceRepository.deleteAllInBatch();
                memberRepository.deleteAllInBatch();
        }

        private Reservation createReservation(ReservationStatus status, LocalDateTime startTime,
                        LocalDateTime endTime, LocalDateTime holdExpiresAt) {
                LocalDateTime checkedInAt = (status == ReservationStatus.IN_USE
                                || status == ReservationStatus.COMPLETED)
                                                ? startTime
                                                : null;
                LocalDateTime checkedOutAt = (status == ReservationStatus.COMPLETED)
                                ? endTime
                                : null;
                LocalDateTime cancelledAt = (status == ReservationStatus.CANCELLED)
                                ? startTime.minusMinutes(30)
                                : null;
                LocalDateTime effectiveHoldExpiresAt = (status == ReservationStatus.HELD)
                                ? (holdExpiresAt != null ? holdExpiresAt : startTime.minusMinutes(10))
                                : holdExpiresAt;

                return reservationRepository.save(Reservation.builder()
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
                                .build());
        }

        private ReservationSlot createSlot(Long reservationId, LocalDateTime slotStart) {
                return reservationSlotRepository.save(ReservationSlot.of(reservationId, spaceId, slotStart));
        }

        @Test
        @DisplayName("유효한 HELD 슬롯은 점유 목록에 포함되고, 만료 시각(now)이 지난 HELD 슬롯은 점유 목록에서 제외된다")
        void findOccupiedSlotStarts_heldSlotExpirationCheck() {
                // given
                LocalDateTime now = LocalDateTime.of(2026, 9, 20, 10, 0, 0);

                // 1. 유효한 HOLD: 만료 시각이 10:05 (now < holdExpiresAt) -> 점유
                Reservation validHeld = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 11, 0),
                                LocalDateTime.of(2026, 9, 20, 11, 30),
                                LocalDateTime.of(2026, 9, 20, 10, 5, 0));
                createSlot(validHeld.getId(), LocalDateTime.of(2026, 9, 20, 11, 0));

                // 2. 정확히 만료된 HOLD: 만료 시각이 10:00 (holdExpiresAt == now) -> 비점유
                Reservation exactExpiredHeld = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 11, 30),
                                LocalDateTime.of(2026, 9, 20, 12, 0),
                                LocalDateTime.of(2026, 9, 20, 10, 0, 0));
                createSlot(exactExpiredHeld.getId(), LocalDateTime.of(2026, 9, 20, 11, 30));

                // 3. 이미 만료된 HOLD: 만료 시각이 09:55 (holdExpiresAt < now) -> 비점유
                Reservation pastExpiredHeld = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 12, 0),
                                LocalDateTime.of(2026, 9, 20, 12, 30),
                                LocalDateTime.of(2026, 9, 20, 9, 55, 0));
                createSlot(pastExpiredHeld.getId(), LocalDateTime.of(2026, 9, 20, 12, 0));

                reservationSlotRepository.flush();
                entityManager.clear();

                LocalDateTime startOfDay = LocalDateTime.of(2026, 9, 20, 0, 0, 0);
                LocalDateTime nextDayStart = LocalDateTime.of(2026, 9, 21, 0, 0, 0);

                // when
                List<LocalDateTime> occupiedSlots = reservationSlotRepository.findOccupiedSlotStarts(
                                spaceId, startOfDay, nextDayStart, now, ReservationStatus.HELD, OCCUPIED_STATUSES);

                // then
                assertThat(occupiedSlots).containsExactly(LocalDateTime.of(2026, 9, 20, 11, 0));
        }

        @Test
        @DisplayName("CONFIRMED, IN_USE, COMPLETED는 점유로 포함되고 EXPIRED, CANCELLED, NO_SHOW는 점유에서 제외된다")
        void findOccupiedSlotStarts_statusFiltering() {
                // given
                LocalDateTime now = LocalDateTime.of(2026, 9, 20, 10, 0, 0);

                Reservation confirmed = createReservation(ReservationStatus.CONFIRMED,
                                LocalDateTime.of(2026, 9, 20, 11, 0), LocalDateTime.of(2026, 9, 20, 11, 30), null);
                createSlot(confirmed.getId(), LocalDateTime.of(2026, 9, 20, 11, 0));

                Reservation inUse = createReservation(ReservationStatus.IN_USE,
                                LocalDateTime.of(2026, 9, 20, 11, 30), LocalDateTime.of(2026, 9, 20, 12, 0), null);
                createSlot(inUse.getId(), LocalDateTime.of(2026, 9, 20, 11, 30));

                Reservation completed = createReservation(ReservationStatus.COMPLETED,
                                LocalDateTime.of(2026, 9, 20, 12, 0), LocalDateTime.of(2026, 9, 20, 12, 30), null);
                createSlot(completed.getId(), LocalDateTime.of(2026, 9, 20, 12, 0));

                Reservation expired = createReservation(ReservationStatus.EXPIRED,
                                LocalDateTime.of(2026, 9, 20, 12, 30), LocalDateTime.of(2026, 9, 20, 13, 0), null);
                createSlot(expired.getId(), LocalDateTime.of(2026, 9, 20, 12, 30));

                Reservation cancelled = createReservation(ReservationStatus.CANCELLED,
                                LocalDateTime.of(2026, 9, 20, 13, 0), LocalDateTime.of(2026, 9, 20, 13, 30), null);
                createSlot(cancelled.getId(), LocalDateTime.of(2026, 9, 20, 13, 0));

                Reservation noShow = createReservation(ReservationStatus.NO_SHOW,
                                LocalDateTime.of(2026, 9, 20, 13, 30), LocalDateTime.of(2026, 9, 20, 14, 0), null);
                createSlot(noShow.getId(), LocalDateTime.of(2026, 9, 20, 13, 30));

                LocalDateTime startOfDay = LocalDateTime.of(2026, 9, 20, 0, 0, 0);
                LocalDateTime nextDayStart = LocalDateTime.of(2026, 9, 21, 0, 0, 0);

                // when
                List<LocalDateTime> occupiedSlots = reservationSlotRepository.findOccupiedSlotStarts(
                                spaceId, startOfDay, nextDayStart, now, ReservationStatus.HELD, OCCUPIED_STATUSES);

                // then
                assertThat(occupiedSlots).containsExactlyInAnyOrder(
                                LocalDateTime.of(2026, 9, 20, 11, 0),
                                LocalDateTime.of(2026, 9, 20, 11, 30),
                                LocalDateTime.of(2026, 9, 20, 12, 0));
        }

        @Test
        @DisplayName("반개구간 조회로 당일 23:30 슬롯은 포함되고 익일 00:00 슬롯은 제외된다")
        void findOccupiedSlotStarts_halfOpenInterval() {
                // given
                LocalDateTime now = LocalDateTime.of(2026, 9, 20, 10, 0, 0);

                Reservation slot2330 = createReservation(ReservationStatus.CONFIRMED,
                                LocalDateTime.of(2026, 9, 20, 23, 30), LocalDateTime.of(2026, 9, 21, 0, 0), null);
                createSlot(slot2330.getId(), LocalDateTime.of(2026, 9, 20, 23, 30));

                Reservation slotNextDay0000 = createReservation(ReservationStatus.CONFIRMED,
                                LocalDateTime.of(2026, 9, 21, 0, 0), LocalDateTime.of(2026, 9, 21, 0, 30), null);
                createSlot(slotNextDay0000.getId(), LocalDateTime.of(2026, 9, 21, 0, 0));

                LocalDateTime startOfDay = LocalDateTime.of(2026, 9, 20, 0, 0, 0);
                LocalDateTime nextDayStart = LocalDateTime.of(2026, 9, 21, 0, 0, 0);

                // when
                List<LocalDateTime> occupiedSlots = reservationSlotRepository.findOccupiedSlotStarts(
                                spaceId, startOfDay, nextDayStart, now, ReservationStatus.HELD, OCCUPIED_STATUSES);

                // then
                assertThat(occupiedSlots).containsExactly(LocalDateTime.of(2026, 9, 20, 23, 30));
        }

        @Test
        @Transactional
        @DisplayName("holdExpiresAt == now 일 때 findExpiredHoldIds 및 expireHeldReservations, deleteSlotsOfExpiredReservations가 정상 동작한다")
        void expireHeldReservations_exactNowMatch() {
                // given
                LocalDateTime now = LocalDateTime.of(2026, 9, 20, 10, 0, 0);

                Reservation exactHold = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 11, 0),
                                LocalDateTime.of(2026, 9, 20, 11, 30),
                                now); // holdExpiresAt == now
                createSlot(exactHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 0));

                Reservation futureHold = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 11, 30),
                                LocalDateTime.of(2026, 9, 20, 12, 0),
                                now.plusSeconds(1)); // holdExpiresAt > now
                createSlot(futureHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 30));

                // when
                List<Long> expiredHoldIds = reservationRepository.findExpiredHoldIds(now, ReservationStatus.HELD);

                // then
                assertThat(expiredHoldIds).containsExactly(exactHold.getId());

                // and when
                int updated = reservationRepository.expireHeldReservations(
                                List.of(exactHold.getId(), futureHold.getId()),
                                now,
                                ReservationStatus.HELD,
                                ReservationStatus.EXPIRED);

                // then
                assertThat(updated).isEqualTo(1);
                Reservation reloadedExact = reservationRepository.findById(exactHold.getId()).orElseThrow();
                Reservation reloadedFuture = reservationRepository.findById(futureHold.getId()).orElseThrow();
                assertThat(reloadedExact.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
                assertThat(reloadedFuture.getStatus()).isEqualTo(ReservationStatus.HELD);

                // and when: 만료된 예약의 슬롯 삭제 수행
                int deletedSlots = reservationSlotRepository.deleteSlotsOfExpiredReservations(
                                List.of(exactHold.getId(), futureHold.getId()),
                                ReservationStatus.EXPIRED);

                // then: exactHold의 슬롯만 삭제되고 futureHold의 슬롯은 유지됨
                assertThat(deletedSlots).isEqualTo(1);
                assertThat(reservationSlotRepository.findAllByReservationId(exactHold.getId())).isEmpty();
                assertThat(reservationSlotRepository.findAllByReservationId(futureHold.getId())).hasSize(1);
        }

        @Test
        @DisplayName("ReservationBatchProcessor.expireHold는 holdExpiresAt == now일 때 EXPIRED 전이, 슬롯 삭제, 이력 저장을 실제 DB에서 수행한다")
        void expireHold_exactNow_deletesSlotsAndSavesHistoryInRealDb() {
                // given
                LocalDateTime now = LocalDateTime.of(2026, 9, 20, 10, 0, 0);

                Reservation exactHold = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 11, 0),
                                LocalDateTime.of(2026, 9, 20, 11, 30),
                                now); // holdExpiresAt == now
                createSlot(exactHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 0));

                // when (테스트 트랜잭션 없이 실제 트랜잭션 커밋 수행)
                boolean expired = reservationBatchProcessor.expireHold(exactHold.getId(), now);

                // then
                assertThat(expired).isTrue();

                Reservation reloaded = reservationRepository.findById(exactHold.getId()).orElseThrow();
                assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.EXPIRED);

                List<ReservationSlot> remainingSlots = reservationSlotRepository
                                .findAllByReservationId(exactHold.getId());
                assertThat(remainingSlots).isEmpty();

                List<ReservationStatusHistory> histories = reservationStatusHistoryRepository
                                .findAllByReservationIdOrderByChangedAtAsc(exactHold.getId());
                assertThat(histories).hasSize(1);
                ReservationStatusHistory history = histories.get(0);
                assertThat(history.getFromStatus()).isEqualTo(ReservationStatus.HELD);
                assertThat(history.getToStatus()).isEqualTo(ReservationStatus.EXPIRED);
                assertThat(history.getReason()).isEqualTo("HOLD_EXPIRED");
                assertThat(history.getChangedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("ReservationSlotService.secureSlots는 holdExpiresAt == now인 기존 HOLD를 정리하고 새 예약 슬롯을 성공적으로 확보한다")
        void secureSlots_exactExpiredHold_cleansUpAndSecuresNewSlotInRealDb() {
                // given (FixedClockConfig의 고정 시각 = 2026-09-17 12:00:00)
                LocalDateTime now = FixedClockConfig.FIXED_DATE_TIME;
                LocalDateTime slotStart = now.plusHours(2); // 14:00:00
                LocalDateTime slotEnd = slotStart.plusMinutes(30);

                // 기존 HOLD: 만료 시각이 정확히 now
                Reservation oldHold = createReservation(ReservationStatus.HELD,
                                slotStart, slotEnd, now);
                createSlot(oldHold.getId(), slotStart);

                // 신규 예약: 동일 슬롯 요청
                Reservation newHold = createReservation(ReservationStatus.HELD,
                                slotStart, slotEnd, now.plusMinutes(10));

                // when (테스트 트랜잭션 없이 실제 secureSlots 트랜잭션 실행)
                reservationSlotService.secureSlots(newHold.getId(), spaceId, List.of(slotStart));

                // then
                Reservation reloadedOld = reservationRepository.findById(oldHold.getId()).orElseThrow();
                assertThat(reloadedOld.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
                assertThat(reservationSlotRepository.findAllByReservationId(oldHold.getId())).isEmpty();

                List<ReservationStatusHistory> oldHistories = reservationStatusHistoryRepository
                                .findAllByReservationIdOrderByChangedAtAsc(oldHold.getId());
                assertThat(oldHistories).hasSize(1);
                ReservationStatusHistory history = oldHistories.get(0);
                assertThat(history.getFromStatus()).isEqualTo(ReservationStatus.HELD);
                assertThat(history.getToStatus()).isEqualTo(ReservationStatus.EXPIRED);
                assertThat(history.getReason()).isEqualTo("HOLD_EXPIRED");
                assertThat(history.getChangedByMemberId()).isNull();
                assertThat(history.getChangedAt()).isEqualTo(now);

                List<ReservationSlot> newSlots = reservationSlotRepository.findAllByReservationId(newHold.getId());
                assertThat(newSlots).hasSize(1);
                assertThat(newSlots.get(0).getSlotStart()).isEqualTo(slotStart);
                assertThat(newSlots.get(0).getReservationId()).isEqualTo(newHold.getId());
        }

        @Test
        @DisplayName("이미 만료된 예약에 대해 expireHold를 다시 시도하면 false를 반환하고 이력이 중복 추가되지 않는다")
        void expireHold_alreadyExpired_returnsFalseAndDoesNotAddDuplicateHistory() {
                // given
                LocalDateTime now = LocalDateTime.of(2026, 9, 20, 10, 0, 0);

                Reservation exactHold = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 11, 0),
                                LocalDateTime.of(2026, 9, 20, 11, 30),
                                now);
                createSlot(exactHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 0));

                boolean firstAttempt = reservationBatchProcessor.expireHold(exactHold.getId(), now);
                assertThat(firstAttempt).isTrue();
                assertThat(reservationStatusHistoryRepository
                                .findAllByReservationIdOrderByChangedAtAsc(exactHold.getId()))
                                .hasSize(1);

                // when
                boolean secondAttempt = reservationBatchProcessor.expireHold(exactHold.getId(), now);

                // then
                assertThat(secondAttempt).isFalse();
                List<ReservationStatusHistory> histories = reservationStatusHistoryRepository
                                .findAllByReservationIdOrderByChangedAtAsc(exactHold.getId());
                assertThat(histories).hasSize(1);
        }

        @Test
        @DisplayName("holdExpiresAt == now + 1초인 미래 HOLD는 만료되지 않고 상태와 슬롯이 유지되며 이력이 생성되지 않는다")
        void expireHold_futureHold_keepsStatusAndSlotsAndCreatesNoHistory() {
                // given
                LocalDateTime now = LocalDateTime.of(2026, 9, 20, 10, 0, 0);

                Reservation futureHold = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 11, 0),
                                LocalDateTime.of(2026, 9, 20, 11, 30),
                                now.plusSeconds(1));
                createSlot(futureHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 0));

                // when
                boolean result = reservationBatchProcessor.expireHold(futureHold.getId(), now);

                // then
                assertThat(result).isFalse();

                Reservation reloaded = reservationRepository.findById(futureHold.getId()).orElseThrow();
                assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.HELD);

                List<ReservationSlot> slots = reservationSlotRepository.findAllByReservationId(futureHold.getId());
                assertThat(slots).hasSize(1);

                List<ReservationStatusHistory> histories = reservationStatusHistoryRepository
                                .findAllByReservationIdOrderByChangedAtAsc(futureHold.getId());
                assertThat(histories).isEmpty();
        }

        @Test
        @DisplayName("findOccupiedSlotStartsEndingAfter는 포함 상태(CONFIRMED, IN_USE, COMPLETED, 유효 HELD)만 조회하고 제외 상태(EXPIRED, CANCELLED, NO_SHOW, 만료 HELD)는 제외한다")
        void findOccupiedSlotStartsEndingAfter_statusFilter_includesOnlyValidOccupied() {
                // given
                LocalDateTime now = LocalDateTime.of(2026, 9, 20, 10, 0, 0);
                LocalDateTime slotStartAfter = now.minusMinutes(30);

                // 포함 대상 4종
                Reservation confirmed = createReservation(ReservationStatus.CONFIRMED,
                                LocalDateTime.of(2026, 9, 20, 11, 0), LocalDateTime.of(2026, 9, 20, 11, 30), null);
                createSlot(confirmed.getId(), LocalDateTime.of(2026, 9, 20, 11, 0));

                Reservation inUse = createReservation(ReservationStatus.IN_USE,
                                LocalDateTime.of(2026, 9, 20, 11, 30), LocalDateTime.of(2026, 9, 20, 12, 0), null);
                createSlot(inUse.getId(), LocalDateTime.of(2026, 9, 20, 11, 30));

                Reservation completed = createReservation(ReservationStatus.COMPLETED,
                                LocalDateTime.of(2026, 9, 20, 12, 0), LocalDateTime.of(2026, 9, 20, 12, 30), null);
                createSlot(completed.getId(), LocalDateTime.of(2026, 9, 20, 12, 0));

                Reservation validHold = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 12, 30), LocalDateTime.of(2026, 9, 20, 13, 0),
                                now.plusSeconds(1));
                createSlot(validHold.getId(), LocalDateTime.of(2026, 9, 20, 12, 30));

                // 제외 대상 4종
                Reservation expired = createReservation(ReservationStatus.EXPIRED,
                                LocalDateTime.of(2026, 9, 20, 13, 0), LocalDateTime.of(2026, 9, 20, 13, 30), null);
                createSlot(expired.getId(), LocalDateTime.of(2026, 9, 20, 13, 0));

                Reservation cancelled = createReservation(ReservationStatus.CANCELLED,
                                LocalDateTime.of(2026, 9, 20, 13, 30), LocalDateTime.of(2026, 9, 20, 14, 0), null);
                createSlot(cancelled.getId(), LocalDateTime.of(2026, 9, 20, 13, 30));

                Reservation noShow = createReservation(ReservationStatus.NO_SHOW,
                                LocalDateTime.of(2026, 9, 20, 14, 0), LocalDateTime.of(2026, 9, 20, 14, 30), null);
                createSlot(noShow.getId(), LocalDateTime.of(2026, 9, 20, 14, 0));

                Reservation expiredHold = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 14, 30), LocalDateTime.of(2026, 9, 20, 15, 0), now);
                createSlot(expiredHold.getId(), LocalDateTime.of(2026, 9, 20, 14, 30));

                // when
                List<LocalDateTime> result = reservationSlotRepository.findOccupiedSlotStartsEndingAfter(
                                spaceId, slotStartAfter, now, ReservationStatus.HELD, OCCUPIED_STATUSES);

                // then
                assertThat(result).containsExactlyInAnyOrder(
                                LocalDateTime.of(2026, 9, 20, 11, 0),
                                LocalDateTime.of(2026, 9, 20, 11, 30),
                                LocalDateTime.of(2026, 9, 20, 12, 0),
                                LocalDateTime.of(2026, 9, 20, 12, 30));
        }

        @Test
        @DisplayName("findOccupiedSlotStartsEndingAfter의 HELD 만료 경계: holdExpiresAt == now는 제외되고 now + 1초는 포함되며 now - 1초는 제외된다")
        void findOccupiedSlotStartsEndingAfter_heldExpiryBoundary() {
                // given
                LocalDateTime now = LocalDateTime.of(2026, 9, 20, 10, 0, 0);
                LocalDateTime slotStartAfter = now.minusMinutes(30);

                // 1. now - 1초 (이미 만료)
                Reservation pastHold = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 11, 0), LocalDateTime.of(2026, 9, 20, 11, 30),
                                now.minusSeconds(1));
                createSlot(pastHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 0));

                // 2. 정확히 now (만료 경계: now < holdExpiresAt 불만족)
                Reservation exactHold = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 11, 30), LocalDateTime.of(2026, 9, 20, 12, 0), now);
                createSlot(exactHold.getId(), LocalDateTime.of(2026, 9, 20, 11, 30));

                // 3. now + 1초 (유효)
                Reservation futureHold = createReservation(ReservationStatus.HELD,
                                LocalDateTime.of(2026, 9, 20, 12, 0), LocalDateTime.of(2026, 9, 20, 12, 30),
                                now.plusSeconds(1));
                createSlot(futureHold.getId(), LocalDateTime.of(2026, 9, 20, 12, 0));

                // when
                List<LocalDateTime> result = reservationSlotRepository.findOccupiedSlotStartsEndingAfter(
                                spaceId, slotStartAfter, now, ReservationStatus.HELD, OCCUPIED_STATUSES);

                // then
                assertThat(result).containsExactly(LocalDateTime.of(2026, 9, 20, 12, 0));
        }

        @Test
        @DisplayName("findOccupiedSlotStartsEndingAfter의 시간 구간 경계: slotStart < now < slotEnd(진행 중), slotStart == now, slotEnd == now 검증")
        void findOccupiedSlotStartsEndingAfter_timeSlotBoundaries() {
                // given
                LocalDateTime now = LocalDateTime.of(2026, 9, 20, 10, 15, 0);
                LocalDateTime slotStartAfter = now.minusMinutes(30); // 09:45

                // 1. 이미 완전히 끝난 슬롯: 09:00 ~ 09:30 (slotStart 09:00 <= 09:45) -> 제외
                Reservation endedBefore = createReservation(ReservationStatus.CONFIRMED,
                                LocalDateTime.of(2026, 9, 20, 9, 0), LocalDateTime.of(2026, 9, 20, 9, 30), null);
                createSlot(endedBefore.getId(), LocalDateTime.of(2026, 9, 20, 9, 0));

                // 2. slotEnd == now 경계: now가 10:00일 때 09:30 ~ 10:00 슬롯
                LocalDateTime nowAtBoundary = LocalDateTime.of(2026, 9, 20, 10, 0, 0);
                LocalDateTime slotStartAfterAtBoundary = nowAtBoundary.minusMinutes(30); // 09:30
                Reservation endedJustNow = createReservation(ReservationStatus.CONFIRMED,
                                LocalDateTime.of(2026, 9, 20, 9, 30), LocalDateTime.of(2026, 9, 20, 10, 0), null);
                createSlot(endedJustNow.getId(), LocalDateTime.of(2026, 9, 20, 9, 30));

                // slotEnd == now인 경우 slotStart (09:30) > slotStartAfter (09:30) 조건에 의해 엄격하게 제외됨
                List<LocalDateTime> boundaryCheck = reservationSlotRepository.findOccupiedSlotStartsEndingAfter(
                                spaceId, slotStartAfterAtBoundary, nowAtBoundary, ReservationStatus.HELD,
                                OCCUPIED_STATUSES);
                assertThat(boundaryCheck).doesNotContain(LocalDateTime.of(2026, 9, 20, 9, 30));

                // 3. slotStart < now < slotEnd (진행 중 슬롯): 10:00 ~ 10:30 (now=10:15)
                // slotStart (10:00) > slotStartAfter (09:45) -> 포함되어야 함!
                Reservation running = createReservation(ReservationStatus.IN_USE,
                                LocalDateTime.of(2026, 9, 20, 10, 0), LocalDateTime.of(2026, 9, 20, 10, 30), null);
                createSlot(running.getId(), LocalDateTime.of(2026, 9, 20, 10, 0));

                // 4. slotStart == now 경계: now가 10:30일 때 10:30 ~ 11:00 슬롯
                LocalDateTime nowExact = LocalDateTime.of(2026, 9, 20, 10, 30, 0);
                LocalDateTime slotStartAfterExact = nowExact.minusMinutes(30); // 10:00
                Reservation startingJustNow = createReservation(ReservationStatus.CONFIRMED,
                                LocalDateTime.of(2026, 9, 20, 10, 30), LocalDateTime.of(2026, 9, 20, 11, 0), null);
                createSlot(startingJustNow.getId(), LocalDateTime.of(2026, 9, 20, 10, 30));

                // when (1): now=10:15 기준 조회
                List<LocalDateTime> resultAt1015 = reservationSlotRepository.findOccupiedSlotStartsEndingAfter(
                                spaceId, slotStartAfter, now, ReservationStatus.HELD, OCCUPIED_STATUSES);

                // then (1): now=10:15 시점에 진행 중인 10:00 슬롯과 이후 10:30 슬롯이 정상 포함됨
                assertThat(resultAt1015).contains(
                                LocalDateTime.of(2026, 9, 20, 10, 0),
                                LocalDateTime.of(2026, 9, 20, 10, 30));
                assertThat(resultAt1015).doesNotContain(
                                LocalDateTime.of(2026, 9, 20, 9, 0),
                                LocalDateTime.of(2026, 9, 20, 9, 30));

                // when (2): nowExact=10:30 기준 조회 (slotStart == nowExact 경계값 검증)
                List<LocalDateTime> resultAt1030 = reservationSlotRepository.findOccupiedSlotStartsEndingAfter(
                                spaceId, slotStartAfterExact, nowExact, ReservationStatus.HELD, OCCUPIED_STATUSES);

                // then (2): slotStart == nowExact인 10:30 슬롯은 포함되고, 10:00 슬롯은 slotEnd(10:30) ==
                // nowExact이므로 제외됨
                assertThat(resultAt1030).contains(LocalDateTime.of(2026, 9, 20, 10, 30));
                assertThat(resultAt1030).doesNotContain(
                                LocalDateTime.of(2026, 9, 20, 9, 0),
                                LocalDateTime.of(2026, 9, 20, 9, 30),
                                LocalDateTime.of(2026, 9, 20, 10, 0));
        }

        @Test
        @DisplayName("격리 통합: 점유 포트(유효/만료 HOLD)와 공간 저장소 영업시간 조건 결합 시 유효 HOLD 및 영업시간 밖 공간 제외, 만료 HOLD 공간 포함 검증")
        void searchSpaces_combinedWithOccupiedSlotProviderAndOperatingHours() {
                // given: 공간 3개 생성
                // 1. 유효 HOLD 공간: 09:00~18:00 영업 (검색 시간 10:00~12:00 포함), 활성
                Space validHoldSpace = spaceRepository.save(Space.builder()
                                .name("유효홀드공간")
                                .location("성남시 분당구")
                                .description("유효한 HOLD가 걸려 있는 공간")
                                .capacity(4)
                                .pricePerSlot(5000L)
                                .openingTime(LocalTime.of(9, 0))
                                .closingTime(LocalTime.of(18, 0))
                                .status(SpaceStatus.ACTIVE)
                                .version(0)
                                .build());

                // 2. 영업시간 밖 비점유 공간: 14:00~22:00 영업 (검색 시간 10:00~12:00 불포함), 예약 없음
                Space outsideHoursSpace = spaceRepository.save(Space.builder()
                                .name("영업시간밖공간")
                                .location("성남시 분당구")
                                .description("검색 시간대 영업하지 않는 비점유 공간")
                                .capacity(6)
                                .pricePerSlot(6000L)
                                .openingTime(LocalTime.of(14, 0))
                                .closingTime(LocalTime.of(22, 0))
                                .status(SpaceStatus.ACTIVE)
                                .version(0)
                                .build());

                // 3. 만료 HOLD 공간: 09:00~18:00 영업 (검색 시간 10:00~12:00 포함), 활성
                Space expiredHoldSpace = spaceRepository.save(Space.builder()
                                .name("만료홀드공간")
                                .location("성남시 분당구")
                                .description("만료된 HOLD만 있어 점유 해제된 공간")
                                .capacity(4)
                                .pricePerSlot(7000L)
                                .openingTime(LocalTime.of(9, 0))
                                .closingTime(LocalTime.of(18, 0))
                                .status(SpaceStatus.ACTIVE)
                                .version(0)
                                .build());

                LocalDateTime now = LocalDateTime.of(2026, 10, 1, 9, 30);

                // validHoldSpace: 유효한 HOLD 예약 생성 (holdExpiresAt = 09:40 > now)
                Reservation validHoldReservation = reservationRepository.save(Reservation.builder()
                                .memberId(memberId)
                                .spaceId(validHoldSpace.getId())
                                .startTime(LocalDateTime.of(2026, 10, 1, 10, 0))
                                .endTime(LocalDateTime.of(2026, 10, 1, 10, 30))
                                .status(ReservationStatus.HELD)
                                .pricePerSlotSnapshot(5000)
                                .totalAmount(5000)
                                .holdExpiresAt(now.plusMinutes(10))
                                .createdAt(now.minusMinutes(10))
                                .build());
                reservationSlotRepository.save(ReservationSlot.of(validHoldReservation.getId(), validHoldSpace.getId(),
                                LocalDateTime.of(2026, 10, 1, 10, 0)));

                // expiredHoldSpace: 만료된 HOLD 예약 생성 (holdExpiresAt = 09:25 < now)
                Reservation expiredHoldReservation = reservationRepository.save(Reservation.builder()
                                .memberId(memberId)
                                .spaceId(expiredHoldSpace.getId())
                                .startTime(LocalDateTime.of(2026, 10, 1, 10, 0))
                                .endTime(LocalDateTime.of(2026, 10, 1, 10, 30))
                                .status(ReservationStatus.HELD)
                                .pricePerSlotSnapshot(7000)
                                .totalAmount(7000)
                                .holdExpiresAt(now.minusMinutes(5))
                                .createdAt(now.minusMinutes(20))
                                .build());
                reservationSlotRepository.save(ReservationSlot.of(expiredHoldReservation.getId(), expiredHoldSpace.getId(),
                                LocalDateTime.of(2026, 10, 1, 10, 0)));

                // when: 점유 포트 조회 (10:00 ~ 12:00, now=09:30)
                LocalDateTime startInclusive = LocalDateTime.of(2026, 10, 1, 10, 0);
                LocalDateTime endExclusive = LocalDateTime.of(2026, 10, 1, 12, 0);
                Set<Long> occupiedSpaceIds = reservationOccupiedSlotAdapter.getOccupiedSpaceIds(
                                startInclusive, endExclusive, now);

                // then (1): 점유 포트 결과 단언
                assertThat(occupiedSpaceIds).contains(validHoldSpace.getId());
                assertThat(occupiedSpaceIds).doesNotContain(expiredHoldSpace.getId());
                assertThat(occupiedSpaceIds).doesNotContain(outsideHoursSpace.getId());

                // when (2): SpaceQueryService와 동일하게 점유 제외 ID와 영업시간 조건을 spaceRepository.searchSpaces에 전달
                List<Long> excludedIds = occupiedSpaceIds.isEmpty() ? List.of(-1L) : new ArrayList<>(occupiedSpaceIds);
                Page<Space> searchResult = spaceRepository.searchSpaces(
                                SpaceStatus.ACTIVE,
                                null, null, null, null,
                                LocalTime.of(10, 0), LocalTime.of(12, 0),
                                excludedIds,
                                PageRequest.of(0, 10, Sort.by("id").ascending()));

                // then (2): 최종 검색 결과 단언
                // - 영업시간 안 유효 HOLD 공간은 점유 포트에 의해 제외됨
                // - 영업시간 밖 비점유 공간은 영업시간 조건(WHERE openingTime <= 10:00 AND closingTime >= 12:00)에 의해 제외됨
                // - 영업시간 안 만료 HOLD 공간은 정상 포함됨
                List<Long> resultSpaceIds = searchResult.getContent().stream().map(Space::getId).toList();
                assertThat(resultSpaceIds).doesNotContain(validHoldSpace.getId());
                assertThat(resultSpaceIds).doesNotContain(outsideHoursSpace.getId());
                assertThat(resultSpaceIds).contains(expiredHoldSpace.getId());
        }
}
