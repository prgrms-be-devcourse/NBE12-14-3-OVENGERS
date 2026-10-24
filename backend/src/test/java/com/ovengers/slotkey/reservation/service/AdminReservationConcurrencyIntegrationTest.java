package com.ovengers.slotkey.reservation.service;

import com.ovengers.slotkey.access.dto.request.DoorAccessVerifyRequest;
import com.ovengers.slotkey.access.dto.response.DoorAccessTokenResponse;
import com.ovengers.slotkey.access.dto.response.DoorAccessVerifyResponse;
import com.ovengers.slotkey.access.entity.AccessDenyReason;
import com.ovengers.slotkey.access.entity.AccessResult;
import com.ovengers.slotkey.access.entity.DoorAccessLog;
import com.ovengers.slotkey.access.entity.DoorAccessToken;
import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository;
import com.ovengers.slotkey.access.service.DoorAccessTokenService;
import com.ovengers.slotkey.access.service.DoorAccessVerificationService;
import com.ovengers.slotkey.access.support.AccessTokenGenerator;
import com.ovengers.slotkey.audit.entity.AuditAction;
import com.ovengers.slotkey.audit.entity.AuditLog;
import com.ovengers.slotkey.audit.entity.AuditTargetType;
import com.ovengers.slotkey.audit.repository.AuditLogRepository;
import com.ovengers.slotkey.credit.entity.CreditTransaction;
import com.ovengers.slotkey.credit.entity.CreditTransactionType;
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository;
import com.ovengers.slotkey.credit.service.CreditGrantService;
import com.ovengers.slotkey.global.config.ClockConfig;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import com.ovengers.slotkey.global.idempotency.IdempotencyKeyRepository;
import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.entity.MemberRole;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.reservation.dto.response.AdminReservationResponse;
import com.ovengers.slotkey.reservation.entity.Reservation;
import com.ovengers.slotkey.reservation.entity.ReservationSlot;
import com.ovengers.slotkey.reservation.entity.ReservationStatus;
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory;
import com.ovengers.slotkey.reservation.repository.ReservationRepository;
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository;
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository;
import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import com.ovengers.slotkey.space.repository.SpaceRepository;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@Import(AdminReservationConcurrencyIntegrationTest.TestClockConfig.class)
class AdminReservationConcurrencyIntegrationTest extends IntegrationTestSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        public static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 21, 10, 0, 0);
        public static final Instant BASE_INSTANT = BASE_TIME.atZone(ClockConfig.DEFAULT_ZONE).toInstant();

        @Bean
        @Primary
        public ControllableClock testClock() {
            return new ControllableClock(Clock.fixed(BASE_INSTANT, ClockConfig.DEFAULT_ZONE));
        }
    }

    public static class ControllableClock extends Clock {
        private final Clock delegate;
        private volatile java.util.function.Consumer<String> onInstantHook;

        public ControllableClock(Clock delegate) {
            this.delegate = delegate;
        }

        public void setOnInstantHook(java.util.function.Consumer<String> hook) {
            this.onInstantHook = hook;
        }

        public void clearHook() {
            this.onInstantHook = null;
        }

        @Override
        public java.time.ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return new ControllableClock(delegate.withZone(zone));
        }

        @Override
        public Instant instant() {
            var hook = onInstantHook;
            if (hook != null) {
                hook.accept(Thread.currentThread().getName());
            }
            return delegate.instant();
        }
    }

    @Autowired
    private AdminReservationService adminReservationService;

    @Autowired
    private DoorAccessVerificationService doorAccessVerificationService;

    @Autowired
    private ReservationPaymentConfirmService reservationPaymentConfirmService;

    @Autowired
    private ReservationExtendService reservationExtendService;

    @org.springframework.boot.test.mock.mockito.SpyBean
    private DoorAccessTokenService doorAccessTokenService;

    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.ovengers.slotkey.access.authorization.DoorAccessAuthorizationService doorAccessAuthorizationService;

    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.ovengers.slotkey.access.policy.DoorAccessTimePolicy doorAccessTimePolicy;

    @org.springframework.boot.test.mock.mockito.SpyBean
    private AccessTokenGenerator accessTokenGenerator;

    @Autowired
    private CreditGrantService creditGrantService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private ReservationStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private DoorAccessTokenRepository doorAccessTokenRepository;

    @Autowired
    private com.ovengers.slotkey.audit.service.AuditLogService auditLogService;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private com.ovengers.slotkey.access.repository.DoorAccessLogRepository doorAccessLogRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SpaceRepository spaceRepository;

    @Autowired
    private com.ovengers.slotkey.reservation.scheduler.ReservationBatchProcessor reservationBatchProcessor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ControllableClock clock;
    @Autowired
    private com.ovengers.slotkey.outbox.OutboxEventRepository outboxEventRepository;

    private Member userMember;
    private Member adminMember;
    private Space space;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        cleanUpAll();
        if (clock != null) {
            clock.clearHook();
        }
        org.mockito.Mockito.reset(
                doorAccessTokenService,
                doorAccessAuthorizationService,
                doorAccessTimePolicy,
                accessTokenGenerator
        );

        executorService = Executors.newFixedThreadPool(4);

        userMember = memberRepository.save(new Member("user@slotkey.test", "예약사용자"));
        creditGrantService.grantSignupCredit(userMember.getId(), 100000);

        adminMember = new Member("admin@slotkey.test",  "최고관리자");
        ReflectionTestUtils.setField(adminMember, "role", MemberRole.ADMIN);
        adminMember = memberRepository.save(adminMember);

        space = spaceRepository.save(Space.builder()
                .name("경합테스트공간")
                .location("서울 강남구")
                .capacity(4)
                .pricePerSlot(3000L)
                .openingTime(LocalTime.of(8, 0))
                .closingTime(LocalTime.of(23, 0))
                .status(SpaceStatus.ACTIVE)
                .build());
    }

    @AfterEach
    void tearDown() {
        if (clock != null) {
            clock.clearHook();
        }
        org.mockito.Mockito.reset(doorAccessTimePolicy);
        if (executorService != null) {
            executorService.shutdownNow();
        }
        cleanUpAll();
    }

    private void cleanUpAll() {
        outboxEventRepository.deleteAllInBatch();
        auditLogRepository.deleteAllInBatch();
        idempotencyKeyRepository.deleteAllInBatch();
        doorAccessLogRepository.deleteAllInBatch();
        creditTransactionRepository.deleteAllInBatch();
        doorAccessTokenRepository.deleteAllInBatch();
        statusHistoryRepository.deleteAllInBatch();
        reservationSlotRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        spaceRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    private TransactionTemplate newRequiresNewTx() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    // ==========================================
    // 1. 실제 멀티스레드 대기·경합 테스트 (결정론적)
    // ==========================================

    @Test
    @DisplayName("실제 경합 1 (결정론적): 체크인이 CONFIRMED 스냅샷을 먼저 생성한 뒤 강제 취소가 커밋되면, 체크인 UPDATE 실패 후 최신 상태 확인으로 출입이 DENY된다")
    void concurrency_checkInSnapshotFirst_cancelCommits_checkInAbortsWithDeny() throws Exception {
        LocalDateTime start = TestClockConfig.BASE_TIME;
        LocalDateTime end = start.plusHours(1);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.CONFIRMED)
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start.plusMinutes(30)));
            return res.getId();
        });

        var tokenResp = doorAccessTokenService.issue(userMember.getId(), reservationId);
        String rawToken = tokenResp.getToken();
        DoorAccessToken token = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow();

        CountDownLatch checkInSnapshotCreated = new CountDownLatch(1);
        CountDownLatch cancelCommitted = new CountDownLatch(1);

        // findDenyReason 호출 시점 = 체크인 트랜잭션이 이미 Reservation(CONFIRMED)을 조회하여 스냅샷이 생성된 시점!
        doAnswer(invocation -> {
            checkInSnapshotCreated.countDown();
            assertThat(cancelCommitted.await(5, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(doorAccessTimePolicy).findDenyReason(any(), any(), any(), any());

        AtomicReference<DoorAccessVerifyResponse> checkInResponseRef = new AtomicReference<>();
        AtomicReference<Exception> checkInExceptionRef = new AtomicReference<>();

        // Thread B: 사용자 최초 체크인 트랜잭션 시작 (CONFIRMED 스냅샷 생성 후 cancelCommitted 대기)
        Future<?> checkInFuture = executorService.submit(() -> {
            try {
                newRequiresNewTx().execute(status -> {
                    DoorAccessVerifyResponse resp = doorAccessVerificationService.verify(
                            userMember.getId(),
                            new DoorAccessVerifyRequest(space.getId(), rawToken)
                    );
                    checkInResponseRef.set(resp);
                    return null;
                });
            } catch (Exception e) {
                checkInExceptionRef.set(e);
            }
        });

        // 체크인이 스냅샷을 생성할 때까지 대기
        assertThat(checkInSnapshotCreated.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread A (메인 스레드): 관리자 강제 취소 실행 및 커밋 완료
        newRequiresNewTx().executeWithoutResult(status -> {
            adminReservationService.forceCancel(reservationId, "동시 강제 취소", adminMember.getId());
        });

        // 강제 취소 커밋 완료를 체크인 스레드에 알림
        cancelCommitted.countDown();

        checkInFuture.get(5, TimeUnit.SECONDS);

        // then: 불변식 단언
        // 1. 응답은 반드시 DENY이고 이유는 RESERVATION_NOT_ACTIVE
        DoorAccessVerifyResponse checkInResp = checkInResponseRef.get();
        assertThat(checkInResp).isNotNull();
        assertThat(checkInResp.getResult()).isEqualTo(AccessResult.DENY);
        assertThat(checkInResp.getReasonCode()).isEqualTo(AccessDenyReason.RESERVATION_NOT_ACTIVE);

        // 2. DB 최종 상태 재조회
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation finalRes = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(finalRes.getCancelledAt()).isNotNull();
            assertThat(finalRes.getCheckedInAt()).isNull();

            // 슬롯은 강제 취소로 0개
            assertThat(reservationSlotRepository.count()).isZero();

            // 토큰은 강제 취소로 폐기됨
            DoorAccessToken reloadedToken = doorAccessTokenRepository.findById(token.getId()).orElseThrow();
            assertThat(reloadedToken.isRevoked()).isTrue();

            // 출입 로그: ALLOW 로그 0건, DENY 로그 1건
            List<DoorAccessLog> logs = doorAccessLogRepository.findAll();
            assertThat(logs).filteredOn(l -> l.getResult() == AccessResult.ALLOW).isEmpty();
            assertThat(logs).filteredOn(l -> l.getResult() == AccessResult.DENY
                    && l.getReasonCode() == AccessDenyReason.RESERVATION_NOT_ACTIVE).hasSize(1);

            // 상태 이력: 최초 체크인(FIRST_CHECK_IN) 이력 0건, 강제 취소 이력 1건
            List<ReservationStatusHistory> histories =
                    statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId);
            assertThat(histories).noneMatch(h -> "FIRST_CHECK_IN".equals(h.getReason()));
            assertThat(histories).hasSize(1);
            assertThat(histories.get(0).getToStatus()).isEqualTo(ReservationStatus.CANCELLED);

            // 감사 로그: FORCE_CANCEL_RESERVATION 1건
            List<AuditLog> auditLogs = auditLogRepository.findAll();
            assertThat(auditLogs).hasSize(1);
            assertThat(auditLogs.get(0).getAction()).isEqualTo(AuditAction.FORCE_CANCEL_RESERVATION);

            // outbox: 강제 취소 커밋 1건
            assertThat(outboxEventRepository.count()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("실제 경합 2 (결정론적): 관리자 강제 취소가 HELD 상태를 조회한 뒤 결제 확인이 커밋되면, 강제 취소는 409 충돌로 거절되고 CONFIRMED가 보존된다")
    void concurrency_forceCancel_vs_paymentConfirm_contention() throws Exception {
        LocalDateTime start = TestClockConfig.BASE_TIME.plusHours(2);
        LocalDateTime end = start.plusHours(1);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.HELD)
                    .holdExpiresAt(start.plusMinutes(10))
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start.plusMinutes(30)));
            return res.getId();
        });

        CountDownLatch cancelReadDone = new CountDownLatch(1);
        CountDownLatch paymentCommitted = new CountDownLatch(1);

        clock.setOnInstantHook(threadName -> {
            if ("force-cancel-worker".equals(threadName)) {
                cancelReadDone.countDown();
                try {
                    assertThat(paymentCommitted.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        AtomicReference<Throwable> cancelExceptionRef = new AtomicReference<>();

        // Thread A: 관리자 강제 취소 (HELD 읽고 now(clock)에서 대기)
        Thread cancelThread = new Thread(() -> {
            try {
                newRequiresNewTx().execute(status -> {
                    adminReservationService.forceCancel(reservationId, "동시 강제 취소", adminMember.getId());
                    return null;
                });
            } catch (Throwable e) {
                cancelExceptionRef.set(e);
            }
        }, "force-cancel-worker");
        cancelThread.start();

        assertThat(cancelReadDone.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread B: 사용자 결제 확인 커밋 완료
        newRequiresNewTx().executeWithoutResult(status -> {
            reservationPaymentConfirmService.confirm(
                    userMember.getId(),
                    reservationId,
                    space.getVersion(),
                    "idempotency-key-payment-concurrency"
            );
        });

        paymentCommitted.countDown();
        cancelThread.join(5000);

        // then: 강제 취소는 409 예외 발생
        assertThat(cancelExceptionRef.get())
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT);

        // DB 재조회: CONFIRMED 유지, 슬롯 2개, 크레딧 차감 1건, 감사 로그 0건
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation reloaded = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(reloaded.getCancelledAt()).isNull();
            assertThat(reservationSlotRepository.count()).isEqualTo(2);

            Member reloadedUser = memberRepository.findById(userMember.getId()).orElseThrow();
            assertThat(reloadedUser.getBalance()).isEqualTo(100000 - 6000);

            List<CreditTransaction> txs = creditTransactionRepository.findAll();
            assertThat(txs).anyMatch(tx -> tx.getType() == CreditTransactionType.RESERVATION_CHARGE
                    && tx.getReservation() != null && tx.getReservation().getId() == reservationId);

            assertThat(auditLogRepository.count()).isZero();
            // outbox: 강제 취소 롤백으로 0건
            assertThat(outboxEventRepository.count()).isZero();
        });
    }

    @Test
    @DisplayName("실제 경합 3 (결정론적): 관리자 강제 취소가 IN_USE 상태를 조회한 뒤 자동 퇴실 배치가 커밋되면, 강제 취소는 409 충돌로 거절되고 COMPLETED 및 슬롯·토큰 폐기·이력이 보존된다")
    void concurrency_forceCancel_vs_autoCheckout_contention() throws Exception {
        LocalDateTime start = TestClockConfig.BASE_TIME.minusHours(2);
        LocalDateTime end = TestClockConfig.BASE_TIME.minusHours(1);

        AtomicReference<DoorAccessToken> tokenRef = new AtomicReference<>();
        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.IN_USE)
                    .checkedInAt(start)
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            DoorAccessToken savedToken = doorAccessTokenRepository.save(new DoorAccessToken(res, "dummy-auto-checkout-hash", start));
            tokenRef.set(savedToken);
            return res.getId();
        });

        DoorAccessToken token = tokenRef.get();

        CountDownLatch cancelReadDone = new CountDownLatch(1);
        CountDownLatch autoCheckoutCommitted = new CountDownLatch(1);

        clock.setOnInstantHook(threadName -> {
            if ("force-cancel-worker".equals(threadName)) {
                cancelReadDone.countDown();
                try {
                    assertThat(autoCheckoutCommitted.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        AtomicReference<Throwable> cancelExceptionRef = new AtomicReference<>();

        // Thread A: 관리자 강제 취소 (IN_USE 읽고 now(clock)에서 대기)
        Thread cancelThread = new Thread(() -> {
            try {
                newRequiresNewTx().execute(status -> {
                    adminReservationService.forceCancel(reservationId, "동시 강제 취소", adminMember.getId());
                    return null;
                });
            } catch (Throwable e) {
                cancelExceptionRef.set(e);
            }
        }, "force-cancel-worker");
        cancelThread.start();

        assertThat(cancelReadDone.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread B: 실제 자동 퇴실 배치 서비스 트랜잭션 실행 및 커밋
        newRequiresNewTx().executeWithoutResult(status -> {
            boolean processed = reservationBatchProcessor.autoCheckOut(reservationId, TestClockConfig.BASE_TIME);
            assertThat(processed).isTrue();
        });

        autoCheckoutCommitted.countDown();
        cancelThread.join(5000);

        // then: 강제 취소는 409 예외 발생
        assertThat(cancelExceptionRef.get())
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT);

        // DB 재조회: COMPLETED 유지, checkedOutAt 기록, 슬롯 1건 보존, 토큰 폐기, AUTO_CHECK_OUT 이력, 강제 취소 감사 로그 0건
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation reloaded = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
            assertThat(reloaded.getCheckedOutAt()).isNotNull();
            assertThat(reloaded.getCancelledAt()).isNull();

            // 슬롯 1건 보존
            assertThat(reservationSlotRepository.count()).isEqualTo(1);

            // 토큰 폐기 확인 (AUTO_CHECK_OUT)
            DoorAccessToken reloadedToken = doorAccessTokenRepository.findById(token.getId()).orElseThrow();
            assertThat(reloadedToken.isRevoked()).isTrue();
            assertThat(reloadedToken.getRevokeReason()).isEqualTo("AUTO_CHECK_OUT");

            // 상태 이력: AUTO_CHECK_OUT 1건
            List<ReservationStatusHistory> histories =
                    statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId);
            assertThat(histories).hasSize(1);
            assertThat(histories.get(0).getFromStatus()).isEqualTo(ReservationStatus.IN_USE);
            assertThat(histories.get(0).getToStatus()).isEqualTo(ReservationStatus.COMPLETED);
            assertThat(histories.get(0).getReason()).isEqualTo("AUTO_CHECK_OUT");

            // 감사 로그: 강제 취소 롤백으로 0건
            assertThat(auditLogRepository.count()).isZero();
            // outbox: 강제 취소 롤백으로 0건
            assertThat(outboxEventRepository.count()).isZero();
        });
    }

    @Test
    @DisplayName("실제 경합 4 (결정론적): 최초 체크인이 CONFIRMED 스냅샷을 만든 뒤, 동시 최초 체크인과 토큰 재발급이 먼저 커밋되면 기존 토큰은 Locking Read로 TOKEN_REVOKED 거절된다")
    void concurrency_checkInSnapshotFirst_reissueAndCheckInCommits_checkInAbortsWithTokenRevoked() throws Exception {
        LocalDateTime start = TestClockConfig.BASE_TIME;
        LocalDateTime end = start.plusHours(1);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.CONFIRMED)
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start.plusMinutes(30)));
            return res.getId();
        });

        var tokenResp = doorAccessTokenService.issue(userMember.getId(), reservationId);
        String oldRawToken = tokenResp.getToken();
        DoorAccessToken oldToken = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow();

        CountDownLatch checkInSnapshotCreated = new CountDownLatch(1);
        CountDownLatch reissueCommitted = new CountDownLatch(1);

        // doorAccessTimePolicy 호출 시점에 latch를 걸어 checkIn 요청이 Read View를 연 뒤 멈추게 함
        doAnswer(invocation -> {
            checkInSnapshotCreated.countDown();
            assertThat(reissueCommitted.await(5, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(doorAccessTimePolicy).findDenyReason(any(), any(), any(), any());

        AtomicReference<DoorAccessVerifyResponse> checkInResponseRef = new AtomicReference<>();
        AtomicReference<Throwable> checkInExceptionRef = new AtomicReference<>();

        // Thread B: 사용자 기존 토큰으로 최초 체크인 트랜잭션 시작 (스냅샷 생성 후 reissueCommitted 대기)
        Future<?> checkInFuture = executorService.submit(() -> {
            try {
                newRequiresNewTx().execute(status -> {
                    DoorAccessVerifyResponse resp = doorAccessVerificationService.verify(
                            userMember.getId(),
                            new DoorAccessVerifyRequest(space.getId(), oldRawToken)
                    );
                    checkInResponseRef.set(resp);
                    return null;
                });
            } catch (Throwable e) {
                checkInExceptionRef.set(e);
            }
        });

        assertThat(checkInSnapshotCreated.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread A (메인 스레드): 동시 최초 체크인 커밋 (IN_USE) + 토큰 재발급 커밋 (기존 토큰 폐기)
        newRequiresNewTx().executeWithoutResult(status -> {
            int updated = reservationRepository.checkInIfConfirmed(
                    reservationId,
                    TestClockConfig.BASE_TIME,
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.IN_USE
            );
            assertThat(updated).isEqualTo(1);

            doorAccessTokenService.issue(userMember.getId(), reservationId);
        });

        reissueCommitted.countDown();
        checkInFuture.get(5, TimeUnit.SECONDS);

        // then: 불변식 단언
        // 1. 기존 토큰 요청은 최신 토큰 상태를 locking read하여 TOKEN_REVOKED 거절되어야 함
        DoorAccessVerifyResponse checkInResp = checkInResponseRef.get();
        assertThat(checkInResp).isNotNull();
        assertThat(checkInResp.getResult()).isEqualTo(AccessResult.DENY);
        assertThat(checkInResp.getReasonCode()).isEqualTo(AccessDenyReason.TOKEN_REVOKED);

        // 2. DB 최종 상태 재조회
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation finalRes = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.IN_USE);

            DoorAccessToken reloadedOldToken = doorAccessTokenRepository.findById(oldToken.getId()).orElseThrow();
            assertThat(reloadedOldToken.isRevoked()).isTrue();
            assertThat(reloadedOldToken.getRevokeReason()).isEqualTo("REISSUED");

            // 출입 로그: ALLOW 로그 0건, DENY 로그 1건 (TOKEN_REVOKED)
            List<DoorAccessLog> logs = doorAccessLogRepository.findAll();
            assertThat(logs).filteredOn(l -> l.getResult() == AccessResult.ALLOW).isEmpty();
            assertThat(logs).filteredOn(l -> l.getResult() == AccessResult.DENY
                    && l.getReasonCode() == AccessDenyReason.TOKEN_REVOKED).hasSize(1);
        });
    }

    @Test
    @DisplayName("실제 경합 5 (결정론적): 최초 체크인이 CONFIRMED 스냅샷을 만든 뒤, 다른 트랜잭션이 토큰만 폐기하고 CONFIRMED를 유지하면 체크인은 Locking Read로 TOKEN_REVOKED 거절된다")
    void concurrency_checkInSnapshotFirst_tokenRevokedAndConfirmedKept_checkInAbortsWithTokenRevoked() throws Exception {
        LocalDateTime start = TestClockConfig.BASE_TIME;
        LocalDateTime end = start.plusHours(1);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.CONFIRMED)
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start.plusMinutes(30)));
            return res.getId();
        });

        var tokenResp = doorAccessTokenService.issue(userMember.getId(), reservationId);
        String oldRawToken = tokenResp.getToken();
        DoorAccessToken oldToken = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow();

        CountDownLatch checkInSnapshotCreated = new CountDownLatch(1);
        CountDownLatch tokenRevokeCommitted = new CountDownLatch(1);

        // doorAccessTimePolicy 호출 시점에 latch를 걸어 checkIn 요청이 Read View를 연 뒤 멈추게 함
        doAnswer(invocation -> {
            checkInSnapshotCreated.countDown();
            assertThat(tokenRevokeCommitted.await(5, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(doorAccessTimePolicy).findDenyReason(any(), any(), any(), any());

        AtomicReference<DoorAccessVerifyResponse> checkInResponseRef = new AtomicReference<>();
        AtomicReference<Throwable> checkInExceptionRef = new AtomicReference<>();

        // Thread B: 사용자 기존 토큰으로 최초 체크인 트랜잭션 시작 (스냅샷 생성 후 tokenRevokeCommitted 대기)
        Future<?> checkInFuture = executorService.submit(() -> {
            try {
                newRequiresNewTx().execute(status -> {
                    DoorAccessVerifyResponse resp = doorAccessVerificationService.verify(
                            userMember.getId(),
                            new DoorAccessVerifyRequest(space.getId(), oldRawToken)
                    );
                    checkInResponseRef.set(resp);
                    return null;
                });
            } catch (Throwable e) {
                checkInExceptionRef.set(e);
            }
        });

        assertThat(checkInSnapshotCreated.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread A (메인 스레드): 사용자가 토큰을 직접 폐기 (예약 상태는 CONFIRMED 그대로 유지)
        newRequiresNewTx().executeWithoutResult(status -> {
            doorAccessTokenService.revokeByReservationId(
                    userMember.getId(),
                    reservationId,
                    TestClockConfig.BASE_TIME,
                    "USER_CANCELLED_TOKEN"
            );
        });

        tokenRevokeCommitted.countDown();
        checkInFuture.get(5, TimeUnit.SECONDS);

        // then: 불변식 단언
        // 1. 체크인 요청은 토큰 Locking Read로 폐기를 감지하여 TOKEN_REVOKED 거절
        assertThat(checkInExceptionRef.get()).isNull();
        DoorAccessVerifyResponse checkInResp = checkInResponseRef.get();
        assertThat(checkInResp).isNotNull();
        assertThat(checkInResp.getResult()).isEqualTo(AccessResult.DENY);
        assertThat(checkInResp.getReasonCode()).isEqualTo(AccessDenyReason.TOKEN_REVOKED);

        // 2. DB 최종 상태 재조회: 예약은 CONFIRMED 유지, 슬롯 보존, 이력 0건, ALLOW 로그 0건
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation finalRes = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(finalRes.getCheckedInAt()).isNull();

            // 슬롯 2건 온전히 보존
            assertThat(reservationSlotRepository.count()).isEqualTo(2);

            // 토큰 폐기 확인
            DoorAccessToken reloadedOldToken = doorAccessTokenRepository.findById(oldToken.getId()).orElseThrow();
            assertThat(reloadedOldToken.isRevoked()).isTrue();
            assertThat(reloadedOldToken.getRevokeReason()).isEqualTo("USER_CANCELLED_TOKEN");

            // 상태 이력: 상태 전이가 실행되지 않았으므로 0건
            List<ReservationStatusHistory> histories =
                    statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId);
            assertThat(histories).isEmpty();

            // 출입 로그: ALLOW 로그 0건, DENY 로그 1건 (TOKEN_REVOKED)
            List<DoorAccessLog> logs = doorAccessLogRepository.findAll();
            assertThat(logs).filteredOn(l -> l.getResult() == AccessResult.ALLOW).isEmpty();
            assertThat(logs).filteredOn(l -> l.getResult() == AccessResult.DENY
                    && l.getReasonCode() == AccessDenyReason.TOKEN_REVOKED).hasSize(1);
        });
    }

    @Test
    @DisplayName("실제 경합 6 (결정론적): 재입장 요청이 IN_USE 스냅샷을 만든 뒤, 관리자 강제 취소가 먼저 커밋되면 재입장은 Locking Read로 RESERVATION_NOT_ACTIVE 거절된다")
    void concurrency_reentrySnapshotFirst_forceCancelCommits_reentryAbortsWithReservationNotActive() throws Exception {
        LocalDateTime start = TestClockConfig.BASE_TIME;
        LocalDateTime end = start.plusHours(1);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.IN_USE)
                    .checkedInAt(start.minusMinutes(1))
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start.plusMinutes(30)));
            return res.getId();
        });

        var tokenResp = doorAccessTokenService.issue(userMember.getId(), reservationId);
        String rawToken = tokenResp.getToken();

        CountDownLatch reentrySnapshotCreated = new CountDownLatch(1);
        CountDownLatch cancelCommitted = new CountDownLatch(1);

        // doorAccessTimePolicy 호출 시점에 latch를 걸어 재입장 verify 요청이 IN_USE 스냅샷을 읽은 뒤 멈추게 함
        doAnswer(invocation -> {
            reentrySnapshotCreated.countDown();
            assertThat(cancelCommitted.await(5, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(doorAccessTimePolicy).findDenyReason(any(), any(), any(), any());

        AtomicReference<DoorAccessVerifyResponse> reentryResponseRef = new AtomicReference<>();
        AtomicReference<Throwable> reentryExceptionRef = new AtomicReference<>();

        // Thread B: 사용자 재입장 트랜잭션 시작 (IN_USE 스냅샷 생성 후 cancelCommitted 대기)
        Future<?> reentryFuture = executorService.submit(() -> {
            try {
                newRequiresNewTx().execute(status -> {
                    DoorAccessVerifyResponse resp = doorAccessVerificationService.verify(
                            userMember.getId(),
                            new DoorAccessVerifyRequest(space.getId(), rawToken)
                    );
                    reentryResponseRef.set(resp);
                    return null;
                });
            } catch (Throwable e) {
                reentryExceptionRef.set(e);
            }
        });

        assertThat(reentrySnapshotCreated.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread A (메인 스레드): 관리자 강제 취소 커밋 (CANCELLED, 슬롯 삭제, 토큰 폐기, 감사 로그)
        newRequiresNewTx().executeWithoutResult(status -> {
            AdminReservationResponse cancelResp = adminReservationService.forceCancel(
                    reservationId,
                    "재입장 직전 관리자 강제 취소",
                    adminMember.getId()
            );
            assertThat(cancelResp.status()).isEqualTo(ReservationStatus.CANCELLED);
        });

        cancelCommitted.countDown();
        reentryFuture.get(5, TimeUnit.SECONDS);

        // then: 불변식 단언
        // 1. 재입장 요청은 최신 예약 Locking Read로 취소를 감지하여 RESERVATION_NOT_ACTIVE 거절
        assertThat(reentryExceptionRef.get()).isNull();
        DoorAccessVerifyResponse reentryResp = reentryResponseRef.get();
        assertThat(reentryResp).isNotNull();
        assertThat(reentryResp.getResult()).isEqualTo(AccessResult.DENY);
        assertThat(reentryResp.getReasonCode()).isEqualTo(AccessDenyReason.RESERVATION_NOT_ACTIVE);

        // 2. DB 최종 상태 재조회: CANCELLED, 슬롯 0개, 추가 ALLOW 로그 0건 (최초 체크인 ALLOW 1건만 존재)
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation finalRes = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(reservationSlotRepository.count()).isZero();

            // 출입 로그: ALLOW 로그 0건, 재입장 거절 DENY 1건
            List<DoorAccessLog> logs = doorAccessLogRepository.findAll();
            assertThat(logs).filteredOn(l -> l.getResult() == AccessResult.ALLOW).isEmpty();
            assertThat(logs).filteredOn(l -> l.getResult() == AccessResult.DENY
                    && l.getReasonCode() == AccessDenyReason.RESERVATION_NOT_ACTIVE).hasSize(1);

            // 상태 이력: FORCE_CANCEL 1건
            List<ReservationStatusHistory> histories =
                    statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId);
            assertThat(histories).hasSize(1);
            assertThat(histories.get(0).getFromStatus()).isEqualTo(ReservationStatus.IN_USE);
            assertThat(histories.get(0).getToStatus()).isEqualTo(ReservationStatus.CANCELLED);

            // 감사 로그: 1건
            assertThat(auditLogRepository.count()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("실제 경합 7: 강제 취소 vs 최초 체크인 동시 출발 시 커밋 순서별 상태·로그·이력 정합성 불변식을 만족한다")
    void concurrency_forceCancel_vs_firstCheckIn_concurrent() throws Exception {
        // given: CONFIRMED 예약, 슬롯 2개, 도어 토큰 생성
        LocalDateTime start = TestClockConfig.BASE_TIME;
        LocalDateTime end = start.plusHours(1);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.CONFIRMED)
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start.plusMinutes(30)));
            return res.getId();
        });

        var tokenResp = doorAccessTokenService.issue(userMember.getId(), reservationId);
        String rawToken = tokenResp.getToken();
        DoorAccessToken token = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow();

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicReference<DoorAccessVerifyResponse> checkInResponseRef = new AtomicReference<>();
        AtomicReference<Throwable> checkInExceptionRef = new AtomicReference<>();
        AtomicReference<AdminReservationResponse> cancelResponseRef = new AtomicReference<>();
        AtomicReference<Throwable> cancelExceptionRef = new AtomicReference<>();

        // Thread A: 관리자 강제 취소
        Future<?> cancelFuture = executorService.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await(5, TimeUnit.SECONDS);
                AdminReservationResponse resp = newRequiresNewTx().execute(status ->
                        adminReservationService.forceCancel(reservationId, "동시 강제 취소", adminMember.getId())
                );
                cancelResponseRef.set(resp);
            } catch (Throwable e) {
                cancelExceptionRef.set(e);
            }
        });

        // Thread B: 사용자 최초 체크인
        Future<?> checkInFuture = executorService.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await(5, TimeUnit.SECONDS);
                DoorAccessVerifyResponse resp = newRequiresNewTx().execute(status ->
                        doorAccessVerificationService.verify(
                                userMember.getId(),
                                new DoorAccessVerifyRequest(space.getId(), rawToken)
                        )
                );
                checkInResponseRef.set(resp);
            } catch (Throwable e) {
                checkInExceptionRef.set(e);
            }
        });

        assertThat(readyLatch.await(3, TimeUnit.SECONDS)).isTrue();
        startLatch.countDown();

        cancelFuture.get(5, TimeUnit.SECONDS);
        checkInFuture.get(5, TimeUnit.SECONDS);

        // then: DB 상태의 일관성 및 선후관계 불변식 단언
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation finalRes = reservationRepository.findById(reservationId).orElseThrow();
            List<ReservationSlot> finalSlots = reservationSlotRepository.findAll();
            DoorAccessToken finalToken = doorAccessTokenRepository.findById(token.getId()).orElseThrow();
            List<AuditLog> auditLogs = auditLogRepository.findAll();
            List<ReservationStatusHistory> histories =
                    statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId);

            if (finalRes.getStatus() == ReservationStatus.CANCELLED) {
                // 강제 취소가 최종 반영된 경우:
                // 1) 강제 취소는 반드시 성공 응답을 받고 예외가 없어야 함
                assertThat(cancelResponseRef.get()).isNotNull();
                assertThat(cancelResponseRef.get().status()).isEqualTo(ReservationStatus.CANCELLED);
                assertThat(cancelExceptionRef.get()).isNull();

                // 2) 체크인 스레드도 예외(락 타임아웃/데드락 등) 없이 정상 종료되어 응답 객체가 반드시 존재해야 함
                assertThat(checkInExceptionRef.get()).isNull();
                DoorAccessVerifyResponse checkInResp = checkInResponseRef.get();
                assertThat(checkInResp).isNotNull();

                // 공통 취소 상태 단언: 슬롯 0개, 토큰 폐기, 감사 로그 1건
                assertThat(finalSlots).isEmpty();
                assertThat(finalToken.isRevoked()).isTrue();
                assertThat(auditLogs).hasSize(1);
                assertThat(auditLogs.get(0).getAction()).isEqualTo(AuditAction.FORCE_CANCEL_RESERVATION);

                // 두 가지 정상 순서 분기 단언:
                if (checkInResp.getResult() == AccessResult.ALLOW) {
                    // 분기 1: 체크인이 먼저 성공 커밋된 후, 관리자가 최신 IN_USE를 강제 취소한 경우
                    assertThat(doorAccessLogRepository.findAll()).filteredOn(l -> l.getResult() == AccessResult.ALLOW).hasSize(1);
                    assertThat(histories).hasSize(2);
                    assertThat(histories.get(0).getFromStatus()).isEqualTo(ReservationStatus.CONFIRMED);
                    assertThat(histories.get(0).getToStatus()).isEqualTo(ReservationStatus.IN_USE);
                    assertThat(histories.get(0).getReason()).isEqualTo("FIRST_CHECK_IN");
                    assertThat(histories.get(1).getFromStatus()).isEqualTo(ReservationStatus.IN_USE);
                    assertThat(histories.get(1).getToStatus()).isEqualTo(ReservationStatus.CANCELLED);
                } else {
                    // 분기 2: 관리자 강제 취소가 먼저 커밋되어 체크인이 거절된 경우
                    assertThat(checkInResp.getResult()).isEqualTo(AccessResult.DENY);
                    assertThat(checkInResp.getReasonCode()).isEqualTo(AccessDenyReason.RESERVATION_NOT_ACTIVE);
                    assertThat(doorAccessLogRepository.findAll()).filteredOn(l -> l.getResult() == AccessResult.ALLOW).isEmpty();
                    assertThat(histories).hasSize(1);
                    assertThat(histories.get(0).getFromStatus()).isEqualTo(ReservationStatus.CONFIRMED);
                    assertThat(histories.get(0).getToStatus()).isEqualTo(ReservationStatus.CANCELLED);
                }
            } else if (finalRes.getStatus() == ReservationStatus.IN_USE) {
                // 체크인이 먼저 완료되고 강제 취소가 409 충돌로 거절된 경우:
                // 1) 체크인은 반드시 성공 응답을 받고 예외가 없어야 함
                assertThat(checkInExceptionRef.get()).isNull();
                DoorAccessVerifyResponse checkInResp = checkInResponseRef.get();
                assertThat(checkInResp).isNotNull();
                assertThat(checkInResp.getResult()).isEqualTo(AccessResult.ALLOW);

                // 2) 강제 취소는 반드시 409 예외가 발생하고 응답이 없어야 함
                assertThat(cancelResponseRef.get()).isNull();
                assertThat(cancelExceptionRef.get())
                        .isInstanceOf(BusinessException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT);

                // 공통 IN_USE 상태 단언
                assertThat(finalSlots).hasSize(2);
                assertThat(finalToken.isRevoked()).isFalse();
                assertThat(auditLogs).isEmpty();
                assertThat(histories).hasSize(1);
                assertThat(histories.get(0).getFromStatus()).isEqualTo(ReservationStatus.CONFIRMED);
                assertThat(histories.get(0).getToStatus()).isEqualTo(ReservationStatus.IN_USE);
            } else {
                org.junit.jupiter.api.Assertions.fail("예상치 못한 최종 상태: " + finalRes.getStatus());
            }
        });
    }

    @Test
    @DisplayName("실제 경합 8 (결정론적): 강제 취소가 Reservation UPDATE 잠금을 보유한 동안 재발급은 locking read 대기로 멈추며(회귀 방지), 취소 커밋 후 409 충돌 거절되고 활성 토큰이 0건이다")
    void concurrency_forceCancelCommitsFirst_reissueAbortsWithConflictAndNoActiveToken() throws Exception {
        LocalDateTime start = TestClockConfig.BASE_TIME;
        LocalDateTime end = start.plusHours(1);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.CONFIRMED)
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start.plusMinutes(30)));
            return res.getId();
        });

        // 초기 토큰 1 발급
        var initialTokenResp = doorAccessTokenService.issue(userMember.getId(), reservationId);
        DoorAccessToken oldToken = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow();

        // 초기 토큰 발급 시 호출된 invocation 기록 초기화
        org.mockito.Mockito.clearInvocations(doorAccessTokenService, doorAccessAuthorizationService, accessTokenGenerator);

        CountDownLatch cancelHoldingLock = new CountDownLatch(1);
        CountDownLatch cancelCanCommit = new CountDownLatch(1);
        CountDownLatch reissueStarted = new CountDownLatch(1);

        AtomicReference<DoorAccessTokenResponse> reissueResponseRef = new AtomicReference<>();
        AtomicReference<Throwable> reissueExceptionRef = new AtomicReference<>();

        // 취소 트랜잭션이 forceCancelIfStatusIs로 Reservation X-Lock을 확보한 직후(revokeByReservation 시점)에 대기하도록 설정
        doAnswer(invocation -> {
            cancelHoldingLock.countDown();
            assertThat(cancelCanCommit.await(5, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(doorAccessTokenService).revokeByReservation(eq(reservationId), any(), any());

        // 재발급 트랜잭션의 issue 진입 관찰
        doAnswer(invocation -> {
            reissueStarted.countDown();
            return invocation.callRealMethod();
        }).when(doorAccessTokenService).issue(eq(userMember.getId()), eq(reservationId));

        // Thread A: 관리자 강제 취소 시작 (Reservation 행 잠금 획득 후 revokeByReservation에서 대기)
        Future<?> cancelFuture = executorService.submit(() -> {
            newRequiresNewTx().executeWithoutResult(status -> {
                AdminReservationResponse cancelResp = adminReservationService.forceCancel(
                        reservationId,
                        "토큰 재발급 전 강제 취소",
                        adminMember.getId()
                );
                assertThat(cancelResp.status()).isEqualTo(ReservationStatus.CANCELLED);
            });
            return null;
        });

        // 취소 트랜잭션이 Reservation X-Lock을 확보했음을 확인
        assertThat(cancelHoldingLock.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread B: 재발급 트랜잭션 시작 (취소가 커밋되지 않은 상태에서 issue 호출)
        Future<?> reissueFuture = executorService.submit(() -> {
            try {
                newRequiresNewTx().execute(status -> {
                    DoorAccessTokenResponse resp = doorAccessTokenService.issue(userMember.getId(), reservationId);
                    reissueResponseRef.set(resp);
                    return null;
                });
            } catch (Throwable e) {
                reissueExceptionRef.set(e);
            }
        });

        // 재발급이 issue 호출에 진입했음을 확인
        assertThat(reissueStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // [결정론적 회귀 방지 단언]:
        // 취소 트랜잭션이 아직 Reservation X-Lock을 쥐고 있으므로,
        // issue()의 첫 문장 findByIdForUpdate는 MySQL InnoDB 행 락 대기 상태에 묶여 있어야 한다.
        // 따라서 다음 단계인 validateOwner와 토큰 생성기 generate()는 취소 커밋 전까지 결코 호출되지 않아야 한다!
        // (만약 과거의 일반 findById로 회귀했다면 MVCC 스냅샷을 읽고 통과하여 이미 validateOwner와 generate를 불렀을 것이므로 실패함!)
        verify(doorAccessAuthorizationService, never()).validateOwner(any(), any());
        verify(accessTokenGenerator, never()).generate();

        // 이제 취소 트랜잭션의 커밋을 허용
        cancelCanCommit.countDown();
        cancelFuture.get(5, TimeUnit.SECONDS);

        // 취소가 커밋되면 재발급 스레드가 Reservation 락을 얻고, 최신 CANCELLED 상태를 감지하여 409 예외 발생
        reissueFuture.get(5, TimeUnit.SECONDS);

        // then: 불변식 단언
        // 1. 재발급 요청은 최신 CANCELLED 상태 감지로 RESERVATION_STATE_CONFLICT 발생
        assertThat(reissueResponseRef.get()).isNull();
        assertThat(reissueExceptionRef.get())
                .isNotNull()
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT);

        // 2. DB 최종 상태: CANCELLED, 슬롯 0개, 활성 토큰 0건, 이전 토큰 ADMIN_FORCE_CANCEL 폐기, 감사 1건
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation finalRes = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(reservationSlotRepository.count()).isZero();

            // 활성 토큰은 0개여야 함
            assertThat(doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId)).isEmpty();

            // 전체 토큰은 1개(초기 토큰)뿐이어야 하고 폐기 상태여야 함
            List<DoorAccessToken> tokens = doorAccessTokenRepository.findAll();
            assertThat(tokens).hasSize(1);
            assertThat(tokens.get(0).getId()).isEqualTo(oldToken.getId());
            assertThat(tokens.get(0).isRevoked()).isTrue();
            assertThat(tokens.get(0).getRevokeReason()).isEqualTo("ADMIN_FORCE_CANCEL");

            // 감사 로그: 1건
            assertThat(auditLogRepository.count()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("실제 경합 9 (결정론적): 토큰 재발급이 먼저 커밋된 뒤 관리자 강제 취소가 실행되면 새로 재발급된 토큰까지 폐기되고 활성 토큰이 0건이다")
    void concurrency_reissueCommitsFirst_forceCancelRevokesNewTokenSuccessfully() throws Exception {
        LocalDateTime start = TestClockConfig.BASE_TIME;
        LocalDateTime end = start.plusHours(1);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.CONFIRMED)
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start.plusMinutes(30)));
            return res.getId();
        });

        // 1. 초기 토큰 1 발급
        var token1Resp = doorAccessTokenService.issue(userMember.getId(), reservationId);
        DoorAccessToken token1 = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow();

        // 2. 토큰 재발급 먼저 커밋: 토큰 1은 REISSUED로 폐기, 새 토큰 2 발급
        var token2Resp = newRequiresNewTx().execute(status ->
                doorAccessTokenService.issue(userMember.getId(), reservationId)
        );
        assertThat(token2Resp).isNotNull();

        // 3. 관리자 강제 취소 실행
        AdminReservationResponse cancelResp = newRequiresNewTx().execute(status ->
                adminReservationService.forceCancel(reservationId, "재발급 후 강제 취소", adminMember.getId())
        );
        assertThat(cancelResp.status()).isEqualTo(ReservationStatus.CANCELLED);

        // then: 불변식 단언
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation finalRes = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(reservationSlotRepository.count()).isZero();

            // 활성 토큰은 반드시 0건이어야 함
            assertThat(doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId)).isEmpty();

            // 총 토큰 수는 2개이며, 둘 다 폐기 상태여야 함
            List<DoorAccessToken> allTokens = doorAccessTokenRepository.findAll();
            assertThat(allTokens).hasSize(2);

            DoorAccessToken reloadedToken1 = doorAccessTokenRepository.findById(token1.getId()).orElseThrow();
            assertThat(reloadedToken1.isRevoked()).isTrue();
            assertThat(reloadedToken1.getRevokeReason()).isEqualTo("REISSUED");

            DoorAccessToken reloadedToken2 = allTokens.stream()
                    .filter(t -> !t.getId().equals(token1.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(reloadedToken2.isRevoked()).isTrue();
            assertThat(reloadedToken2.getRevokeReason()).isEqualTo("ADMIN_FORCE_CANCEL");

            // 감사 로그: 1건
            assertThat(auditLogRepository.count()).isEqualTo(1);
            // 상태 이력: 1건
            assertThat(statusHistoryRepository.count()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("실제 경합 10 (결정론적): 출입 검증이 Reservation 잠금을 보유한 동안 재발급은 Reservation 잠금에서 대기하며(Token 미진입), 역순 교착 없이 순차적으로 모두 완료된다")
    void concurrency_verifyHoldsReservationLock_reissueWaitsWithoutDeadlock() throws Exception {
        LocalDateTime start = TestClockConfig.BASE_TIME;
        LocalDateTime end = start.plusHours(1);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.CONFIRMED)
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start.plusMinutes(30)));
            return res.getId();
        });

        var initialTokenResp = doorAccessTokenService.issue(userMember.getId(), reservationId);
        String rawToken = initialTokenResp.getToken();
        DoorAccessToken initialToken = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow();

        // 초기 토큰 발급 시 호출된 invocation 기록 초기화
        org.mockito.Mockito.clearInvocations(doorAccessTokenService, doorAccessAuthorizationService, accessTokenGenerator);

        CountDownLatch verifyHoldingReservationLock = new CountDownLatch(1);
        CountDownLatch verifyCanProceed = new CountDownLatch(1);
        CountDownLatch reissueStarted = new CountDownLatch(1);

        AtomicReference<DoorAccessVerifyResponse> verifyResponseRef = new AtomicReference<>();
        AtomicReference<Throwable> verifyExceptionRef = new AtomicReference<>();
        AtomicReference<DoorAccessTokenResponse> reissueResponseRef = new AtomicReference<>();
        AtomicReference<Throwable> reissueExceptionRef = new AtomicReference<>();

        // 재발급 트랜잭션의 issue 진입 관찰
        doAnswer(invocation -> {
            reissueStarted.countDown();
            return invocation.callRealMethod();
        }).when(doorAccessTokenService).issue(eq(userMember.getId()), eq(reservationId));

        // Thread A: 검증 트랜잭션이 선행 Reservation 잠금을 보유한 상태에서 재발급 대기를 관찰하고, 이후 검증을 정상 완료
        Future<?> verifyFuture = executorService.submit(() -> {
            try {
                DoorAccessVerifyResponse resp = newRequiresNewTx().execute(status -> {
                    // 1. Reservation 비관적 락 선행 획득
                    reservationRepository.findByIdForUpdate(reservationId).orElseThrow();
                    verifyHoldingReservationLock.countDown();

                    // 2. Thread B(재발급)가 Reservation 잠금에 묶여 대기 중임을 외부에서 확인할 때까지 대기
                    try {
                        assertThat(verifyCanProceed.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }

                    // 3. 실제 출입 검증 완료 (동일 트랜잭션 내에서 ALLOW 판정)
                    return doorAccessVerificationService.verify(
                            userMember.getId(),
                            new DoorAccessVerifyRequest(space.getId(), rawToken)
                    );
                });
                verifyResponseRef.set(resp);
            } catch (Throwable e) {
                verifyExceptionRef.set(e);
            }
        });

        // Thread A가 Reservation Locking Read를 마쳤음을 확인
        assertThat(verifyHoldingReservationLock.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread B: 토큰 재발급 호출 (Reservation -> DoorAccessToken 순서이므로 Reservation 잠금에서 대기해야 함)
        Future<?> reissueFuture = executorService.submit(() -> {
            try {
                DoorAccessTokenResponse resp = newRequiresNewTx().execute(status ->
                        doorAccessTokenService.issue(userMember.getId(), reservationId)
                );
                reissueResponseRef.set(resp);
            } catch (Throwable e) {
                reissueExceptionRef.set(e);
            }
        });

        // Thread B가 재발급 issue 호출에 진입했음을 확인
        assertThat(reissueStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // [결정론적 단언]:
        // Thread A가 Reservation 잠금을 보유 중이므로,
        // Thread B는 findByIdForUpdate에서 MySQL 행 락 대기 상태에 묶여 있다.
        // 따라서 다음 단계인 validateOwner와 토큰 생성기 generate()에는 아직 결코 도달하지 못했어야 한다!
        verify(doorAccessAuthorizationService, never()).validateOwner(any(), any());
        verify(accessTokenGenerator, never()).generate();

        // 이제 Thread A (검증)가 계속 진행하여 검증 커밋 및 Reservation 잠금을 해제하도록 허용
        verifyCanProceed.countDown();

        // 데드락(교착) 없이 두 작업 모두 10초 내 정상 완료되어야 함
        verifyFuture.get(10, TimeUnit.SECONDS);
        reissueFuture.get(10, TimeUnit.SECONDS);

        // then: 교착 없이 두 트랜잭션 모두 예외 없이 정상 종료
        assertThat(verifyExceptionRef.get()).isNull();
        assertThat(reissueExceptionRef.get()).isNull();

        assertThat(verifyResponseRef.get()).isNotNull();
        assertThat(verifyResponseRef.get().getResult()).isEqualTo(AccessResult.ALLOW);

        assertThat(reissueResponseRef.get()).isNotNull();

        // DB 최종 상태 확인: 상태는 IN_USE, 활성 토큰 1개 존재
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation finalRes = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.IN_USE);

            // 활성 토큰은 재발급된 새 토큰 1개
            DoorAccessToken activeToken = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow();
            assertThat(activeToken.getId()).isNotEqualTo(initialToken.getId());

            // 총 토큰 2개 (구 토큰 REISSUED, 신규 토큰 활성)
            List<DoorAccessToken> tokens = doorAccessTokenRepository.findAll();
            assertThat(tokens).hasSize(2);
        });
    }

    // ==========================================
    // 2. 순차 계약 테스트 (상태 정책 및 전이 계약 검증)
    // ==========================================

    @Test
    @DisplayName("순차 계약 1: 최초 체크인이 완전히 커밋되어 IN_USE인 예약은 관리자 강제 취소가 성공하며 CANCELLED, 슬롯 삭제, 토큰 폐기, 감사 로그가 남는다")
    void contract_checkInCompleted_cancelSucceedsWithAdminAudit() {
        // given: CONFIRMED 예약 및 체크인 완료 (IN_USE)
        LocalDateTime start = TestClockConfig.BASE_TIME;
        LocalDateTime end = start.plusHours(1);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.CONFIRMED)
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start.plusMinutes(30)));
            return res.getId();
        });

        var tokenResp = doorAccessTokenService.issue(userMember.getId(), reservationId);
        DoorAccessToken token = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow();

        // 1. 체크인 실행 및 완료
        newRequiresNewTx().executeWithoutResult(status -> {
            DoorAccessVerifyResponse resp = doorAccessVerificationService.verify(
                    userMember.getId(),
                    new DoorAccessVerifyRequest(space.getId(), tokenResp.getToken())
            );
            assertThat(resp.getResult()).isEqualTo(AccessResult.ALLOW);
        });

        // 2. IN_USE 상태에서 관리자 강제 취소 실행 -> 현재 서비스 정책상 정상 성공!
        newRequiresNewTx().executeWithoutResult(status -> {
            AdminReservationResponse cancelResp = adminReservationService.forceCancel(
                    reservationId,
                    "체크인 후 강제 취소",
                    adminMember.getId()
            );
            assertThat(cancelResp.status()).isEqualTo(ReservationStatus.CANCELLED);
        });

        // 3. DB 최종 상태 단언: CANCELLED, 슬롯 삭제(0개), 토큰 폐기, IN_USE -> CANCELLED 이력, 감사 로그 1건
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation reloaded = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(reloaded.getCancelledAt()).isNotNull();
            assertThat(reservationSlotRepository.count()).isZero();

            DoorAccessToken reloadedToken = doorAccessTokenRepository.findById(token.getId()).orElseThrow();
            assertThat(reloadedToken.isRevoked()).isTrue();

            List<ReservationStatusHistory> histories =
                    statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId);
            assertThat(histories).hasSize(2);
            assertThat(histories.get(0).getReason()).isEqualTo("FIRST_CHECK_IN");
            assertThat(histories.get(1).getFromStatus()).isEqualTo(ReservationStatus.IN_USE);
            assertThat(histories.get(1).getToStatus()).isEqualTo(ReservationStatus.CANCELLED);

            List<AuditLog> auditLogs = auditLogRepository.findAll();
            assertThat(auditLogs).hasSize(1);
            assertThat(auditLogs.get(0).getAction()).isEqualTo(AuditAction.FORCE_CANCEL_RESERVATION);
        });
    }

    @Test
    @DisplayName("순차 계약 2: 관리자 강제 취소가 이미 커밋되어 CANCELLED인 상태에서 강제 취소 재호출 시 RESERVATION_STATE_CONFLICT로 거절된다")
    void contract_cancelCompleted_reCancelAbortsWithAlreadyCancelled() {
        LocalDateTime start = TestClockConfig.BASE_TIME.plusHours(1);
        LocalDateTime end = start.plusHours(2);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.CONFIRMED)
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            return res.getId();
        });

        // 1. 1차 강제 취소 완료 커밋 (CONFIRMED -> CANCELLED)
        newRequiresNewTx().executeWithoutResult(status -> {
            adminReservationService.forceCancel(reservationId, "1차 강제 취소", adminMember.getId());
        });

        // 2. 이미 CANCELLED 상태인 예약에 대해 다시 forceCancel 호출 시 RESERVATION_STATE_CONFLICT 발생
        assertThatThrownBy(() -> adminReservationService.forceCancel(reservationId, "2차 강제 취소", adminMember.getId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT);

        // 3. 감사 로그는 1차 취소 1건만 존재
        newRequiresNewTx().executeWithoutResult(status -> {
            assertThat(auditLogRepository.count()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("순차 계약 3: 관리자 강제 취소가 완전히 커밋되어 CANCELLED인 상태에서 사용자의 연장 시도는 거절되고 CANCELLED가 보존된다")
    void contract_cancelCompleted_extendAbortsWithDisallowed() {
        LocalDateTime start = TestClockConfig.BASE_TIME.plusHours(1);
        LocalDateTime end = start.plusHours(1);

        Long reservationId = newRequiresNewTx().execute(status -> {
            Reservation res = reservationRepository.save(Reservation.builder()
                    .memberId(userMember.getId())
                    .spaceId(space.getId())
                    .startTime(start)
                    .endTime(end)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .status(ReservationStatus.CONFIRMED)
                    .createdAt(start.minusDays(1))
                    .build());

            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start));
            reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.getId(), start.plusMinutes(30)));
            return res.getId();
        });

        // 1. 관리자 강제 취소 완료 커밋 (CONFIRMED -> CANCELLED)
        newRequiresNewTx().executeWithoutResult(status -> {
            adminReservationService.forceCancel(reservationId, "연장 전 강제 취소", adminMember.getId());
        });

        // 2. 사용자가 연장을 시도하면 RESERVATION_EXTEND_NOT_ALLOWED 예외 발생
        LocalDateTime expectedEndTime = end;
        LocalDateTime newEndTime = end.plusMinutes(30);

        assertThatThrownBy(() -> reservationExtendService.extend(userMember.getId(), reservationId, expectedEndTime, newEndTime))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_EXTEND_NOT_ALLOWED);

        // 3. DB 재조회: CANCELLED 상태 유지, 슬롯은 0개, 추가 차감 없음
        newRequiresNewTx().executeWithoutResult(status -> {
            Reservation reloaded = reservationRepository.findById(reservationId).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(reservationSlotRepository.count()).isZero();

            Member reloadedUser = memberRepository.findById(userMember.getId()).orElseThrow();
            assertThat(reloadedUser.getBalance()).isEqualTo(100000 + 6000);
        });
    }
}
