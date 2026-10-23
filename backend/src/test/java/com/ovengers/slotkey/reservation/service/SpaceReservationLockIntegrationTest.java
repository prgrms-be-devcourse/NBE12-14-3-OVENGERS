package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.audit.repository.AuditLogRepository;
import com.ovengers.slotkey.global.config.ClockConfig;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import com.ovengers.slotkey.space.service.AdminSpaceService;
import com.ovengers.slotkey.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(SpaceReservationLockIntegrationTest.LockTestClockConfig.class)
class SpaceReservationLockIntegrationTest extends IntegrationTestSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class LockTestClockConfig {
        public static final LocalDateTime FIXED_DATE_TIME = LocalDateTime.of(2026, 9, 20, 10, 15, 0);
        public static final Instant FIXED_INSTANT = FIXED_DATE_TIME.atZone(ClockConfig.DEFAULT_ZONE).toInstant();

        @Bean
        @Primary
        public Clock testClock() {
            return Clock.fixed(FIXED_INSTANT, ClockConfig.DEFAULT_ZONE);
        }
    }

    @Autowired
    private AdminSpaceService adminSpaceService;

    @Autowired
    private ReservationHoldService reservationHoldService;

    @Autowired
    private ReservationPaymentConfirmService reservationPaymentConfirmService;

    @Autowired
    private ReservationExtendService reservationExtendService;

    @Autowired
    private SpaceRepository spaceRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private com.ovengers.slotkey.global.idempotency.IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private com.ovengers.slotkey.credit.repository.CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private com.ovengers.slotkey.credit.service.CreditGrantService creditGrantService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private ReservationStatusHistoryRepository reservationStatusHistoryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long memberId;
    private Long spaceId;

    @BeforeEach
    void setUp() {
        cleanup();

        Member member = memberRepository.save(new Member(
                "locktest-" + System.nanoTime() + "@slotkey.test",
                "테스트유저"));
        memberId = member.getId();

        Space space = spaceRepository.save(Space.builder()
                .name("잠금 테스트 회의실")
                .location("테헤란로 100")
                .capacity(6)
                .pricePerSlot(2000L)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(22, 0))
                .status(SpaceStatus.ACTIVE)
                .version(0)
                .build());
        spaceId = space.getId();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        auditLogRepository.deleteAllInBatch();
        idempotencyKeyRepository.deleteAllInBatch();
        creditTransactionRepository.deleteAllInBatch();
        reservationStatusHistoryRepository.deleteAllInBatch();
        reservationSlotRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        spaceRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("공간 수정 트랜잭션이 비관적 배타 락을 쥐고 있는 동안에는 HOLD 요청이 대기(직렬화)된다")
    void spaceUpdate_locksHoldRequest() throws Exception {
        CountDownLatch spaceLockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch holdAttemptStartedLatch = new CountDownLatch(1);
        CountDownLatch releaseSpaceLockLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicBoolean holdFinishedBeforeSpaceRelease = new AtomicBoolean(false);

        // Thread 1: AdminSpaceService.updateSpace 트랜잭션 (수동 트랜잭션으로 Lock 유지 시뮬레이션)
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Future<?> spaceUpdateFuture = executor.submit(() -> {
            txTemplate.execute(status -> {
                // 배타 락 획득
                Space space = spaceRepository.findByIdForUpdate(spaceId).orElseThrow();
                spaceLockAcquiredLatch.countDown();

                try {
                    // Thread 2가 HOLD를 시도할 시간을 줌
                    releaseSpaceLockLatch.await(5, TimeUnit.SECONDS);
                    space.updateDetail(new SpaceUpdateRequest(
                            null, "이름변경", null, null, null, null, null, null, null, null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        // Thread 1이 락을 잡을 때까지 대기
        assertThat(spaceLockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Thread 2: ReservationHoldService.createHold 호출 (공유 락 획득 시도 -> 대기해야 함)
        LocalDateTime start = LocalDateTime.now(clock).plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Future<?> holdFuture = executor.submit(() -> {
            holdAttemptStartedLatch.countDown();
            ReservationResponse hold = reservationHoldService.createHold(memberId, spaceId, start, end);
            if (releaseSpaceLockLatch.getCount() > 0) {
                holdFinishedBeforeSpaceRelease.set(true);
            }
            return hold;
        });

        assertThat(holdAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();
        // HOLD가 즉시 끝나지 않고 블록되어 있어야 함
        Thread.sleep(300);
        assertThat(holdFinishedBeforeSpaceRelease.get()).isFalse();

        // 공간 락 해제
        releaseSpaceLockLatch.countDown();

        spaceUpdateFuture.get(5, TimeUnit.SECONDS);
        Object holdResult = holdFuture.get(5, TimeUnit.SECONDS);
        assertThat(holdResult).isNotNull();

        executor.shutdown();
    }

    @Test
    @DisplayName("시나리오 1: 관리자 배타 락 선행 -> HOLD 요청이 대기 후 변경된 새 운영시간으로 거절된다")
    void concurrency_adminExclusiveLockFirst_thenHoldEvaluatesWithNewHours() throws Exception {
        CountDownLatch adminLockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch holdAttemptStartedLatch = new CountDownLatch(1);
        CountDownLatch releaseAdminLockLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Thread 1: 관리자가 운영시간을 11:00 ~ 22:00으로 축소 (배타 락 획득 후 대기)
        Future<?> adminFuture = executor.submit(() -> {
            txTemplate.execute(status -> {
                Space space = spaceRepository.findByIdForUpdate(spaceId).orElseThrow();
                adminLockAcquiredLatch.countDown();
                try {
                    releaseAdminLockLatch.await(5, TimeUnit.SECONDS);
                    space.updateDetail(new SpaceUpdateRequest(
                            null, null, null, null, null, null, null,
                            LocalTime.of(11, 0), LocalTime.of(22, 0), null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        assertThat(adminLockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Thread 2: 사용자가 10:00 ~ 11:00 HOLD 시도 (관리자 트랜잭션 종료 대기 후, 변경된 11:00 시작시간으로 인해
        // INVALID_RESERVATION_TIME 실패)
        LocalDateTime start = LocalDateTime.now(clock).plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        AtomicBoolean holdBlocked = new AtomicBoolean(true);
        Future<?> holdFuture = executor.submit(() -> {
            holdAttemptStartedLatch.countDown();
            try {
                reservationHoldService.createHold(memberId, spaceId, start, end);
                holdBlocked.set(false);
                return null;
            } catch (Exception e) {
                holdBlocked.set(false);
                return e;
            }
        });

        assertThat(holdAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
        assertThat(holdBlocked.get()).isTrue(); // 아직 락 대기 중이어야 함

        releaseAdminLockLatch.countDown();
        adminFuture.get(5, TimeUnit.SECONDS);

        Object holdException = holdFuture.get(5, TimeUnit.SECONDS);
        assertThat(holdException).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) holdException).getErrorCode()).isEqualTo(ErrorCode.INVALID_RESERVATION_TIME);

        executor.shutdown();
    }

    @Test
    @DisplayName("시나리오 2: HOLD/연장 공유 락 선행 -> 관리자 축소가 대기 후 새 점유를 보고 409 거절된다")
    void concurrency_holdSharedLockFirst_thenAdminShrinkFailsWithConflict() throws Exception {
        CountDownLatch holdLockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch adminAttemptStartedLatch = new CountDownLatch(1);
        CountDownLatch releaseHoldLockLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        LocalDateTime start = LocalDateTime.now(clock).plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // Thread 1: HOLD 생성 트랜잭션 (Space 공유 락 획득 후 슬롯 저장하고 대기)
        Future<?> holdFuture = executor.submit(() -> {
            txTemplate.execute(status -> {
                reservationHoldService.createHold(memberId, spaceId, start, end);
                holdLockAcquiredLatch.countDown();
                try {
                    releaseHoldLockLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        assertThat(holdLockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Thread 2: 관리자가 11:00 ~ 22:00으로 운영시간 축소 시도 (공유 락 때문에 배타 락 대기)
        AtomicBoolean adminBlocked = new AtomicBoolean(true);
        Future<?> adminFuture = executor.submit(() -> {
            adminAttemptStartedLatch.countDown();
            try {
                adminSpaceService.updateSpace(spaceId, new SpaceUpdateRequest(
                        null, null, null, null, null, null, null,
                        LocalTime.of(11, 0), LocalTime.of(22, 0), null), 100L);
                adminBlocked.set(false);
                return null;
            } catch (Exception e) {
                adminBlocked.set(false);
                return e;
            }
        });

        assertThat(adminAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
        assertThat(adminBlocked.get()).isTrue(); // 관리자가 락 대기 중이어야 함

        releaseHoldLockLatch.countDown();
        holdFuture.get(5, TimeUnit.SECONDS);

        // HOLD가 커밋된 후 관리자는 최신 점유 슬롯(10:00~11:00)을 발견하고 409 예외 발생
        Object adminException = adminFuture.get(5, TimeUnit.SECONDS);
        assertThat(adminException).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) adminException).getErrorCode())
                .isEqualTo(ErrorCode.SPACE_OPERATING_HOURS_CONFLICT);

        executor.shutdown();
    }

    @Test
    @DisplayName("시나리오 3: 가격 수정 선행 -> 결제 시도가 대기 후 SPACE_VERSION_MISMATCH 예외가 발생한다")
    void concurrency_priceUpdateFirst_thenPaymentFailsWithVersionMismatch() throws Exception {
        // 먼저 2000원(version 0) 시점에 HOLD 생성 및 회원 크레딧 100,000 충전
        creditGrantService.grantSignupCredit(memberId, 100000);
        LocalDateTime start = LocalDateTime.now(clock).plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);
        ReservationResponse hold = reservationHoldService.createHold(memberId, spaceId, start, end);
        assertThat(hold.spaceVersion()).isEqualTo(0);

        CountDownLatch adminPriceLockLatch = new CountDownLatch(1);
        CountDownLatch payAttemptStartedLatch = new CountDownLatch(1);
        CountDownLatch releaseAdminPriceLockLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Thread 1: 관리자가 가격을 3000원으로 변경 (version 0 -> 1 증가, 커밋 전까지 락 유지)
        Future<?> adminFuture = executor.submit(() -> {
            txTemplate.execute(status -> {
                Space space = spaceRepository.findByIdForUpdate(spaceId).orElseThrow();
                adminPriceLockLatch.countDown();
                try {
                    releaseAdminPriceLockLatch.await(5, TimeUnit.SECONDS);
                    space.updateDetail(new SpaceUpdateRequest(
                            null, null, null, null, null, 3000L, null, null, null, null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        assertThat(adminPriceLockLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Thread 2: 사용자가 기존 version 0으로 결제 확인 시도 -> Space 공유 락 대기 후 version 1을 읽고
        // SPACE_VERSION_MISMATCH
        AtomicBoolean payBlocked = new AtomicBoolean(true);
        Future<?> payFuture = executor.submit(() -> {
            payAttemptStartedLatch.countDown();
            try {
                reservationPaymentConfirmService.confirm(memberId, hold.reservationId(), 0, "idemp-ver-mismatch");
                payBlocked.set(false);
                return null;
            } catch (Exception e) {
                payBlocked.set(false);
                return e;
            }
        });

        assertThat(payAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
        assertThat(payBlocked.get()).isTrue();

        releaseAdminPriceLockLatch.countDown();
        adminFuture.get(5, TimeUnit.SECONDS);

        Object payException = payFuture.get(5, TimeUnit.SECONDS);
        assertThat(payException).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) payException).getErrorCode()).isEqualTo(ErrorCode.SPACE_VERSION_MISMATCH);

        executor.shutdown();
    }

    @Test
    @DisplayName("시나리오 4: 결제 공유 락 선행 -> 기존 HOLD 가격으로 확정된 뒤 가격 수정이 완료된다")
    void concurrency_paymentSharedLockFirst_thenConfirmedAtOldPriceBeforePriceUpdate() throws Exception {
        creditGrantService.grantSignupCredit(memberId, 100000);
        LocalDateTime start = LocalDateTime.now(clock).plusDays(1).withHour(15).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);
        ReservationResponse hold = reservationHoldService.createHold(memberId, spaceId, start, end);

        CountDownLatch payLockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch adminAttemptStartedLatch = new CountDownLatch(1);
        CountDownLatch releasePayLockLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        java.util.concurrent.atomic.AtomicReference<ReservationResponse> confirmedRef = new java.util.concurrent.atomic.AtomicReference<>();
        Future<?> payFuture = executor.submit(() -> {
            txTemplate.execute(status -> {
                ReservationResponse confirmed = reservationPaymentConfirmService.confirm(
                        memberId, hold.reservationId(), 0, "idemp-pay-first");
                confirmedRef.set(confirmed);
                payLockAcquiredLatch.countDown();
                try {
                    releasePayLockLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        assertThat(payLockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Thread 2: 관리자가 가격을 3000원으로 변경 시도 (Space 공유 락 때문에 배타 락 대기)
        AtomicBoolean adminBlocked = new AtomicBoolean(true);
        Future<?> adminFuture = executor.submit(() -> {
            adminAttemptStartedLatch.countDown();
            try {
                adminSpaceService.updateSpace(spaceId, new SpaceUpdateRequest(
                        null, null, null, null, null, 3000L, null, null, null, null), 100L);
                adminBlocked.set(false);
                return null;
            } catch (Exception e) {
                adminBlocked.set(false);
                return e;
            }
        });

        assertThat(adminAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
        assertThat(adminBlocked.get()).isTrue();

        // 결제 락 해제 -> 결제 커밋 -> 관리자 가격 수정 완료
        releasePayLockLatch.countDown();
        payFuture.get(5, TimeUnit.SECONDS);
        assertThat(confirmedRef.get()).isNotNull();

        adminFuture.get(5, TimeUnit.SECONDS);

        // 검증: 예약은 기존 단가(2000원)로 CONFIRMED 완료되었고, 공간은 새 가격(3000원, version 1)으로 변경됨
        Reservation reservation = reservationRepository.findById(hold.reservationId()).orElseThrow();
        assertThat(reservation.getStatus())
                .isEqualTo(com.ovengers.slotkey.reservation.entity.ReservationStatus.CONFIRMED);
        assertThat(reservation.getPricePerSlotSnapshot()).isEqualTo(2000);
        assertThat(reservation.getTotalAmount()).isEqualTo(4000);

        Space updatedSpace = spaceRepository.findById(spaceId).orElseThrow();
        assertThat(updatedSpace.getPricePerSlot()).isEqualTo(3000L);
        assertThat(updatedSpace.getVersion()).isEqualTo(1);

        executor.shutdown();
    }

    @Test
    @DisplayName("미래 유효 슬롯이 축소 대상 범위에 걸치면 409 SPACE_OPERATING_HOURS_CONFLICT 가 발생한다")
    void updateSpace_conflictWithFutureSlot_throwsConflict() {
        // 내일 날짜로 고정된 공간 예약 설정: 09:30 ~ 10:30 슬롯
        LocalDateTime tomorrow = LocalDateTime.now(clock).plusDays(1);
        LocalDateTime start = tomorrow.withHour(9).withMinute(30).withSecond(0).withNano(0);
        LocalDateTime end = tomorrow.withHour(10).withMinute(30).withSecond(0).withNano(0);
        reservationHoldService.createHold(memberId, spaceId, start, end);

        // 관리자가 운영 시작 시각을 10:00으로 늦추려고 시도
        // 09:30~10:00 슬롯이 새 운영시간(10:00~) 밖이므로 충돌 발생해야 함
        SpaceUpdateRequest shrinkRequest = new SpaceUpdateRequest(
                null, null, null, null, null, null, null,
                LocalTime.of(10, 0), LocalTime.of(22, 0), null);

        assertThatThrownBy(() -> adminSpaceService.updateSpace(spaceId, shrinkRequest, 100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_OPERATING_HOURS_CONFLICT);

        Space unmodified = spaceRepository.findById(spaceId).orElseThrow();
        assertThat(unmodified.getOpeningTime()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    @DisplayName("실제 현재 진행 중인 슬롯(slotStart < now < slotEnd)이 축소 대상 범위에 걸치면 409가 발생하고 Space 및 AuditLog가 변경되지 않는다")
    void updateSpace_conflictWithOngoingSlot_throwsConflictAndRollsBack() {
        LocalDateTime now = LocalDateTime.now(clock);
        // 고정 Clock 기준 (now = 10:15:00): slotStart(10:00) < now(10:15) < slotEnd(10:30) 명시적 구성
        LocalDateTime slotStart = now.toLocalDate().atTime(10, 0);
        LocalDateTime slotEnd = now.toLocalDate().atTime(10, 30);
        assertThat(slotStart).isBefore(now);
        assertThat(now).isBefore(slotEnd);

        Reservation running = reservationRepository.save(Reservation.builder()
                .memberId(memberId)
                .spaceId(spaceId)
                .startTime(slotStart)
                .endTime(slotEnd)
                .status(com.ovengers.slotkey.reservation.entity.ReservationStatus.IN_USE)
                .pricePerSlotSnapshot(2000)
                .totalAmount(2000)
                .createdAt(slotStart.minusMinutes(30))
                .checkedInAt(slotStart)
                .build());
        reservationSlotRepository.save(com.ovengers.slotkey.reservation.entity.ReservationSlot.of(
                running.getId(), spaceId, slotStart));

        long initialAuditCount = auditLogRepository.count();

        // 운영 시작 시간을 slotStart 이후인 10:30으로 축소 시도 (기존 09:00 -> 10:30)
        LocalTime newOpeningTime = LocalTime.of(10, 30);
        SpaceUpdateRequest shrinkRequest = new SpaceUpdateRequest(
                null, null, null, null, null, null, null,
                newOpeningTime, LocalTime.of(22, 0), null);

        assertThatThrownBy(() -> adminSpaceService.updateSpace(spaceId, shrinkRequest, 100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_OPERATING_HOURS_CONFLICT);

        // 롤백 확인: Space 운영시간 및 감사로그 변경 없음
        Space unmodified = spaceRepository.findById(spaceId).orElseThrow();
        assertThat(unmodified.getOpeningTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(auditLogRepository.count()).isEqualTo(initialAuditCount);
    }

    @Test
    @DisplayName("연장 트랜잭션이 Space 공유 락 및 Reservation 배타 락을 획득하면 관리자 운영시간 축소 및 반대 Reservation 갱신이 블록되고 커밋 후 최종 반영된다")
    void concurrency_extendLocksSpaceAndReservation_blocksConflictingUpdates() throws Exception {
        // 크레딧 50000 충전 및 확정된 예약 생성 (11:00 ~ 13:00)
        creditGrantService.grantAdminCredit(memberId, 50000, "연장 동시성 테스트 충전");

        LocalDateTime tomorrow = LocalDateTime.now(clock).plusDays(1);
        LocalDateTime start = tomorrow.withHour(11).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = tomorrow.withHour(13).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime newEnd = tomorrow.withHour(14).withMinute(0).withSecond(0).withNano(0);

        ReservationResponse hold = reservationHoldService.createHold(memberId, spaceId, start, end);
        reservationPaymentConfirmService.confirm(memberId, hold.reservationId(), 0, "idemp-extend-concurrency");

        CountDownLatch extendLockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch secondTxAttemptStartedLatch = new CountDownLatch(1);
        CountDownLatch secondTxPassedSpaceLockLatch = new CountDownLatch(1);
        CountDownLatch adminAttemptStartedLatch = new CountDownLatch(1);
        CountDownLatch releaseExtendLockLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            // Thread 1: 연장 트랜잭션 (Space 공유 락 및 Reservation 배타 락 획득 후 대기)
            java.util.concurrent.atomic.AtomicReference<ReservationResponse> extendResultRef = new java.util.concurrent.atomic.AtomicReference<>();
            Future<?> extendFuture = executor.submit(() -> {
                txTemplate.execute(status -> {
                    ReservationResponse extended = reservationExtendService.extend(
                            memberId, hold.reservationId(), end, newEnd);
                    extendResultRef.set(extended);
                    extendLockAcquiredLatch.countDown();
                    try {
                        releaseExtendLockLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            });

            assertThat(extendLockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue();

            // Thread 2: 동일 Reservation에 대한 반대 연장 시도 (13:00 기준 15:00 연장 시도)
            // Space 공유 락을 통과한 뒤, 동일 Reservation 배타 락(findByIdForUpdate)에서 블록되어야 함
            AtomicBoolean secondTxBlocked = new AtomicBoolean(true);
            Future<?> secondExtendFuture = executor.submit(() -> {
                secondTxAttemptStartedLatch.countDown();
                try {
                    return txTemplate.execute(status -> {
                        // 잠금 순서에 따라 Space 공유 락 먼저 통과 확인
                        Long targetSpaceId = reservationRepository.findSpaceIdById(hold.reservationId()).orElseThrow();
                        spaceRepository.findByIdForShare(targetSpaceId).orElseThrow();
                        secondTxPassedSpaceLockLatch.countDown();

                        // 동일 예약 연장 시도 -> 내부의 findByIdForUpdate에서 Thread 1 커밋 전까지 블록됨
                        ReservationResponse result = reservationExtendService.extend(
                                memberId, hold.reservationId(), end, newEnd.plusHours(1));
                        secondTxBlocked.set(false);
                        return result;
                    });
                } catch (Exception e) {
                    secondTxBlocked.set(false);
                    return e;
                }
            });

            // Thread 2가 Space 공유 락을 정상 획득했음을 확인
            assertThat(secondTxAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(secondTxPassedSpaceLockLatch.await(3, TimeUnit.SECONDS)).isTrue();

            // Thread 3: 관리자가 운영시간을 13:30까지로 축소 시도
            // Space 공유 락(Thread 1, 2)으로 인해 Space 배타 락에서 대기 후 충돌 감지되어 409 실패해야 함
            AtomicBoolean adminBlocked = new AtomicBoolean(true);
            Future<?> adminFuture = executor.submit(() -> {
                adminAttemptStartedLatch.countDown();
                try {
                    adminSpaceService.updateSpace(spaceId, new SpaceUpdateRequest(
                            null, null, null, null, null, null, null,
                            null, LocalTime.of(13, 30), null), 100L);
                    adminBlocked.set(false);
                    return null;
                } catch (Exception e) {
                    adminBlocked.set(false);
                    return e;
                }
            });

            assertThat(adminAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);

            // Thread 2는 Space 공유 락 통과 후 Reservation 배타 락에서 대기 중이어야 함
            assertThat(secondTxBlocked.get()).isTrue();
            assertThat(secondExtendFuture.isDone()).isFalse();

            // Thread 3은 Space 배타 락에서 대기 중이어야 함
            assertThat(adminBlocked.get()).isTrue();
            assertThat(adminFuture.isDone()).isFalse();

            // 연장 트랜잭션(Thread 1) 락 해제 및 커밋
            releaseExtendLockLatch.countDown();
            extendFuture.get(5, TimeUnit.SECONDS);
            assertThat(extendResultRef.get()).isNotNull();

            // Thread 2는 Reservation 배타 락 대기 해제 후, 예약 정보가 14:00으로 변경되었으므로 RESERVATION_STATE_CONFLICT 발생
            Object secondResult = secondExtendFuture.get(5, TimeUnit.SECONDS);
            assertThat(secondResult).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) secondResult).getErrorCode())
                    .isEqualTo(ErrorCode.RESERVATION_STATE_CONFLICT);

            // Thread 3 관리자 트랜잭션은 Space 락 해제 후 커밋된 13:00~14:00 슬롯을 감지하여 409 SPACE_OPERATING_HOURS_CONFLICT 발생
            Object adminResult = adminFuture.get(5, TimeUnit.SECONDS);
            assertThat(adminResult).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) adminResult).getErrorCode())
                    .isEqualTo(ErrorCode.SPACE_OPERATING_HOURS_CONFLICT);

            // DB 최종 상태 검증
            Reservation finalReservation = reservationRepository.findById(hold.reservationId()).orElseThrow();
            assertThat(finalReservation.getEndTime()).isEqualTo(newEnd);
            assertThat(finalReservation.getStatus())
                    .isEqualTo(com.ovengers.slotkey.reservation.entity.ReservationStatus.CONFIRMED);
            // 11:00~13:00 (4슬롯 * 2000원 = 8000원) + Thread 1 연장 13:00~14:00 (2슬롯 * 2000원 = 4000원) = 12000원
            assertThat(finalReservation.getTotalAmount()).isEqualTo(12000);

            // 슬롯: 11:00부터 14:00까지 정확히 6개 슬롯 존재 확인 (Thread 2 롤백으로 14:00~15:00 슬롯 미생성)
            List<com.ovengers.slotkey.reservation.entity.ReservationSlot> finalSlots =
                    reservationSlotRepository.findAllByReservationId(hold.reservationId());
            assertThat(finalSlots).hasSize(6);

            // 크레딧: 50,000 - 8,000(초기결제) - 4,000(Thread 1 연장) = 38,000원 (Thread 2 롤백으로 추가 차감 없음)
            Member member = memberRepository.findById(memberId).orElseThrow();
            assertThat(member.getBalance()).isEqualTo(38000);

            // 공간: 운영시간 22:00 유지 (Thread 3 롤백)
            Space unmodifiedSpace = spaceRepository.findById(spaceId).orElseThrow();
            assertThat(unmodifiedSpace.getClosingTime()).isEqualTo(LocalTime.of(22, 0));
        } finally {
            executor.shutdownNow();
        }
    }
}
