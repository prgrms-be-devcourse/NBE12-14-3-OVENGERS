package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.access.dto.request.DoorAccessVerifyRequest
import com.ovengers.slotkey.access.dto.response.DoorAccessTokenResponse
import com.ovengers.slotkey.access.dto.response.DoorAccessVerifyResponse
import com.ovengers.slotkey.access.entity.AccessDenyReason
import com.ovengers.slotkey.access.entity.AccessResult
import com.ovengers.slotkey.access.entity.DoorAccessToken
import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository
import com.ovengers.slotkey.access.service.DoorAccessTokenService
import com.ovengers.slotkey.access.service.DoorAccessVerificationService
import com.ovengers.slotkey.access.support.AccessTokenGenerator
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.credit.service.CreditGrantService
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.idempotency.IdempotencyKeyRepository
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.dto.response.AdminReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationSlot
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.eqNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Import(AdminReservationConcurrencyIntegrationTest.TestClockConfig::class)
class AdminReservationConcurrencyIntegrationTest : IntegrationTestSupport() {
    @TestConfiguration(proxyBeanMethods = false)
    class TestClockConfig {
        companion object {
            val BASE_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 21, 10, 0, 0)
            val BASE_INSTANT: Instant = BASE_TIME.atZone(ClockConfig.DEFAULT_ZONE).toInstant()
        }

        @Bean
        @Primary
        fun testClock(): ControllableClock = ControllableClock(Clock.fixed(BASE_INSTANT, ClockConfig.DEFAULT_ZONE))
    }

    class ControllableClock(
        private val delegate: Clock,
    ) : Clock() {
        @Volatile
        private var onInstantHook: java.util.function.Consumer<String>? = null

        fun setOnInstantHook(hook: java.util.function.Consumer<String>) {
            this.onInstantHook = hook
        }

        fun clearHook() {
            this.onInstantHook = null
        }

        override fun getZone(): java.time.ZoneId = delegate.zone

        override fun withZone(zone: java.time.ZoneId): Clock = ControllableClock(delegate.withZone(zone))

        override fun instant(): Instant {
            val hook = onInstantHook
            if (hook != null) {
                hook.accept(Thread.currentThread().name)
            }
            return delegate.instant()
        }
    }

    @Autowired
    private lateinit var adminReservationService: AdminReservationService

    @Autowired
    private lateinit var doorAccessVerificationService: DoorAccessVerificationService

    @Autowired
    private lateinit var reservationPaymentConfirmService: ReservationPaymentConfirmService

    @Autowired
    private lateinit var reservationExtendService: ReservationExtendService

    @org.springframework.boot.test.mock.mockito.SpyBean
    private lateinit var doorAccessTokenService: DoorAccessTokenService

    @org.springframework.boot.test.mock.mockito.SpyBean
    private lateinit var doorAccessAuthorizationService: com.ovengers.slotkey.access.authorization.DoorAccessAuthorizationService

    @org.springframework.boot.test.mock.mockito.SpyBean
    private lateinit var doorAccessTimePolicy: com.ovengers.slotkey.access.policy.DoorAccessTimePolicy

    @org.springframework.boot.test.mock.mockito.SpyBean
    private lateinit var accessTokenGenerator: AccessTokenGenerator

    @Autowired
    private lateinit var creditGrantService: CreditGrantService

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var reservationSlotRepository: ReservationSlotRepository

    @Autowired
    private lateinit var statusHistoryRepository: ReservationStatusHistoryRepository

    @Autowired
    private lateinit var doorAccessTokenRepository: DoorAccessTokenRepository

    @Autowired
    private lateinit var auditLogService: com.ovengers.slotkey.audit.service.AuditLogService

    @Autowired
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Autowired
    private lateinit var idempotencyKeyRepository: IdempotencyKeyRepository

    @Autowired
    private lateinit var doorAccessLogRepository: com.ovengers.slotkey.access.repository.DoorAccessLogRepository

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var reservationBatchProcessor: com.ovengers.slotkey.reservation.scheduler.ReservationBatchProcessor

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var clock: ControllableClock

    @Autowired
    private lateinit var outboxEventRepository: com.ovengers.slotkey.outbox.OutboxEventRepository

    private lateinit var userMember: Member
    private lateinit var adminMember: Member
    private lateinit var space: Space
    private lateinit var executorService: ExecutorService

    @BeforeEach
    fun setUp() {
        cleanUpAll()
        if (this::clock.isInitialized) {
            clock.clearHook()
        }
        org.mockito.Mockito.reset(
            doorAccessTokenService,
            doorAccessAuthorizationService,
            doorAccessTimePolicy,
            accessTokenGenerator,
        )

        executorService = Executors.newFixedThreadPool(4)

        userMember = memberRepository.save(Member("user@slotkey.test", "예약사용자"))
        creditGrantService.grantSignupCredit(userMember.id!!, 100000)

        adminMember = Member("admin@slotkey.test", "최고관리자")
        ReflectionTestUtils.setField(adminMember, "role", MemberRole.ADMIN)
        adminMember = memberRepository.save(adminMember)

        space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("경합테스트공간")
                    .location("서울 강남구")
                    .capacity(4)
                    .pricePerSlot(3000L)
                    .openingTime(LocalTime.of(8, 0))
                    .closingTime(LocalTime.of(23, 0))
                    .status(SpaceStatus.ACTIVE)
                    .build(),
            )
    }

    @AfterEach
    fun tearDown() {
        if (this::clock.isInitialized) {
            clock.clearHook()
        }
        org.mockito.Mockito.reset(doorAccessTimePolicy)
        if (this::executorService.isInitialized) {
            executorService.shutdownNow()
        }
        cleanUpAll()
    }

    private fun cleanUpAll() {
        outboxEventRepository.deleteAllInBatch()
        auditLogRepository.deleteAllInBatch()
        idempotencyKeyRepository.deleteAllInBatch()
        doorAccessLogRepository.deleteAllInBatch()
        creditTransactionRepository.deleteAllInBatch()
        doorAccessTokenRepository.deleteAllInBatch()
        statusHistoryRepository.deleteAllInBatch()
        reservationSlotRepository.deleteAllInBatch()
        reservationRepository.deleteAllInBatch()
        spaceRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
    }

    private fun newRequiresNewTx(): TransactionTemplate {
        val tx = TransactionTemplate(transactionManager)
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        return tx
    }

    // ==========================================
    // 1. 실제 멀티스레드 대기·경합 테스트 (결정론적)
    // ==========================================

    @Test
    @DisplayName("실제 경합 1 (결정론적): 체크인이 CONFIRMED 스냅샷을 먼저 생성한 뒤 강제 취소가 커밋되면, 체크인 UPDATE 실패 후 최신 상태 확인으로 출입이 DENY된다")
    fun concurrency_checkInSnapshotFirst_cancelCommits_checkInAbortsWithDeny() {
        val start = TestClockConfig.BASE_TIME
        val end = start.plusHours(1)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.CONFIRMED)
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start.plusMinutes(30)))
                return@execute res.getId()
            })!!

        val tokenResp = doorAccessTokenService.issue(userMember.id!!, reservationId)
        val rawToken = tokenResp.token
        val token = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow()

        val checkInSnapshotCreated = CountDownLatch(1)
        val cancelCommitted = CountDownLatch(1)

        // findDenyReason 호출 시점 = 체크인 트랜잭션이 이미 Reservation(CONFIRMED)을 조회하여 스냅샷이 생성된 시점!
        doAnswer({ invocation ->
            checkInSnapshotCreated.countDown()
            assertThat(cancelCommitted.await(5, TimeUnit.SECONDS)).isTrue()
            return@doAnswer invocation.callRealMethod()
        }).`when`(doorAccessTimePolicy).findDenyReason(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull())

        val checkInResponseRef = AtomicReference<DoorAccessVerifyResponse>()
        val checkInExceptionRef = AtomicReference<Exception>()

        // Thread B: 사용자 최초 체크인 트랜잭션 시작 (CONFIRMED 스냅샷 생성 후 cancelCommitted 대기)
        val checkInFuture =
            executorService.submit(
                Runnable {
                    try {
                        newRequiresNewTx().execute({ _ ->
                            val resp =
                                doorAccessVerificationService.verify(
                                    userMember.id!!,
                                    DoorAccessVerifyRequest(space.id!!, rawToken),
                                )
                            checkInResponseRef.set(resp)
                            return@execute null
                        })
                    } catch (e: Exception) {
                        checkInExceptionRef.set(e)
                    }
                },
            )

        // 체크인이 스냅샷을 생성할 때까지 대기
        assertThat(checkInSnapshotCreated.await(5, TimeUnit.SECONDS)).isTrue()

        // Thread A (메인 스레드): 관리자 강제 취소 실행 및 커밋 완료
        newRequiresNewTx().executeWithoutResult({ _ ->
            adminReservationService.forceCancel(reservationId, "동시 강제 취소", adminMember.id!!)
        })

        // 강제 취소 커밋 완료를 체크인 스레드에 알림
        cancelCommitted.countDown()

        checkInFuture.get(5, TimeUnit.SECONDS)

        // then: 불변식 단언
        // 1. 응답은 반드시 DENY이고 이유는 RESERVATION_NOT_ACTIVE
        val checkInResp = checkInResponseRef.get()
        assertThat(checkInResp).isNotNull()
        assertThat(checkInResp.result).isEqualTo(AccessResult.DENY)
        assertThat(checkInResp.reasonCode).isEqualTo(AccessDenyReason.RESERVATION_NOT_ACTIVE)

        // 2. DB 최종 상태 재조회
        newRequiresNewTx().executeWithoutResult({ _ ->
            val finalRes = reservationRepository.findById(reservationId).orElseThrow()
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CANCELLED)
            assertThat(finalRes.cancelledAt).isNotNull()
            assertThat(finalRes.checkedInAt).isNull()

            // 슬롯은 강제 취소로 0개
            assertThat(reservationSlotRepository.count()).isZero()

            // 토큰은 강제 취소로 폐기됨
            val reloadedToken = doorAccessTokenRepository.findById(token.id!!).orElseThrow()
            assertThat(reloadedToken.isRevoked).isTrue()

            // 출입 로그: ALLOW 로그 0건, DENY 로그 1건
            val logs = doorAccessLogRepository.findAll()
            assertThat(logs).filteredOn({ l -> l.result == AccessResult.ALLOW }).isEmpty()
            assertThat(logs)
                .filteredOn({ l ->
                    l.result == AccessResult.DENY &&
                        l.reasonCode == AccessDenyReason.RESERVATION_NOT_ACTIVE
                })
                .hasSize(1)

            // 상태 이력: 최초 체크인(FIRST_CHECK_IN) 이력 0건, 강제 취소 이력 1건
            val histories = statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId)
            assertThat(histories).noneMatch({ h -> "FIRST_CHECK_IN".equals(h.reason) })
            assertThat(histories).hasSize(1)
            assertThat(histories.get(0).toStatus).isEqualTo(ReservationStatus.CANCELLED)

            // 감사 로그: FORCE_CANCEL_RESERVATION 1건
            val auditLogs = auditLogRepository.findAll()
            assertThat(auditLogs).hasSize(1)
            assertThat(auditLogs.get(0).action).isEqualTo(AuditAction.FORCE_CANCEL_RESERVATION)

            // outbox: 강제 취소 커밋으로 빈자리 이벤트 1건과 예약자 알림 이벤트 1건
            assertThat(outboxEventRepository.findAll().map { it.topic }).containsExactlyInAnyOrder("slot-released", "notification-events")
        })
    }

    @Test
    @DisplayName("실제 경합 2 (결정론적): 관리자 강제 취소가 HELD 상태를 조회한 뒤 결제 확인이 커밋되면, 강제 취소는 409 충돌로 거절되고 CONFIRMED가 보존된다")
    fun concurrency_forceCancel_vs_paymentConfirm_contention() {
        val start = TestClockConfig.BASE_TIME.plusHours(2)
        val end = start.plusHours(1)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.HELD)
                            .holdExpiresAt(start.plusMinutes(10))
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start.plusMinutes(30)))
                return@execute res.getId()
            })!!

        val cancelReadDone = CountDownLatch(1)
        val paymentCommitted = CountDownLatch(1)

        clock.setOnInstantHook({ threadName ->
            if ("force-cancel-worker".equals(threadName)) {
                cancelReadDone.countDown()
                try {
                    assertThat(paymentCommitted.await(5, TimeUnit.SECONDS)).isTrue()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        })

        val cancelExceptionRef = AtomicReference<Throwable>()

        // Thread A: 관리자 강제 취소 (HELD 읽고 now(clock)에서 대기)
        val cancelThread =
            Thread({
                try {
                    newRequiresNewTx().execute({ _ ->
                        adminReservationService.forceCancel(reservationId, "동시 강제 취소", adminMember.id!!)
                        return@execute null
                    })
                } catch (e: Throwable) {
                    cancelExceptionRef.set(e)
                }
            }, "force-cancel-worker")
        cancelThread.start()

        assertThat(cancelReadDone.await(5, TimeUnit.SECONDS)).isTrue()

        // Thread B: 사용자 결제 확인 커밋 완료
        newRequiresNewTx().executeWithoutResult({ _ ->
            reservationPaymentConfirmService.confirm(
                userMember.id!!,
                reservationId,
                space.version,
                "idempotency-key-payment-concurrency",
            )
        })

        paymentCommitted.countDown()
        cancelThread.join(5000)

        // then: 강제 취소는 409 예외 발생
        assertThat(cancelExceptionRef.get())
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

        // DB 재조회: CONFIRMED 유지, 슬롯 2개, 크레딧 차감 1건, 감사 로그 0건
        newRequiresNewTx().executeWithoutResult({ _ ->
            val reloaded = reservationRepository.findById(reservationId).orElseThrow()
            assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CONFIRMED)
            assertThat(reloaded.cancelledAt).isNull()
            assertThat(reservationSlotRepository.count()).isEqualTo(2)

            val reloadedUser = memberRepository.findById(userMember.id!!).orElseThrow()
            assertThat(reloadedUser.balance).isEqualTo(100000 - 6000)

            val txs = creditTransactionRepository.findAll()
            assertThat(txs).anyMatch({ tx ->
                tx.type == CreditTransactionType.RESERVATION_CHARGE &&
                    tx.reservation != null &&
                    tx.reservation!!.getId() == reservationId
            })

            assertThat(auditLogRepository.count()).isZero()
            // outbox: 강제 취소 롤백으로 0건
            assertThat(outboxEventRepository.count()).isZero()
        })
    }

    @Test
    @DisplayName("실제 경합 3 (결정론적): 관리자 강제 취소가 IN_USE 상태를 조회한 뒤 자동 퇴실 배치가 커밋되면, 강제 취소는 409 충돌로 거절되고 COMPLETED 및 슬롯·토큰 폐기·이력이 보존된다")
    fun concurrency_forceCancel_vs_autoCheckout_contention() {
        val start = TestClockConfig.BASE_TIME.minusHours(2)
        val end = TestClockConfig.BASE_TIME.minusHours(1)

        val tokenRef = AtomicReference<DoorAccessToken>()
        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.IN_USE)
                            .checkedInAt(start)
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                val savedToken = doorAccessTokenRepository.save(DoorAccessToken(res, "dummy-auto-checkout-hash", start))
                tokenRef.set(savedToken)
                return@execute res.getId()
            })!!

        val token = tokenRef.get()

        val cancelReadDone = CountDownLatch(1)
        val autoCheckoutCommitted = CountDownLatch(1)

        clock.setOnInstantHook({ threadName ->
            if ("force-cancel-worker".equals(threadName)) {
                cancelReadDone.countDown()
                try {
                    assertThat(autoCheckoutCommitted.await(5, TimeUnit.SECONDS)).isTrue()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        })

        val cancelExceptionRef = AtomicReference<Throwable>()

        // Thread A: 관리자 강제 취소 (IN_USE 읽고 now(clock)에서 대기)
        val cancelThread =
            Thread({
                try {
                    newRequiresNewTx().execute({ _ ->
                        adminReservationService.forceCancel(reservationId, "동시 강제 취소", adminMember.id!!)
                        return@execute null
                    })
                } catch (e: Throwable) {
                    cancelExceptionRef.set(e)
                }
            }, "force-cancel-worker")
        cancelThread.start()

        assertThat(cancelReadDone.await(5, TimeUnit.SECONDS)).isTrue()

        // Thread B: 실제 자동 퇴실 배치 서비스 트랜잭션 실행 및 커밋
        newRequiresNewTx().executeWithoutResult({ _ ->
            val processed = reservationBatchProcessor.autoCheckOut(reservationId, TestClockConfig.BASE_TIME)
            assertThat(processed).isTrue()
        })

        autoCheckoutCommitted.countDown()
        cancelThread.join(5000)

        // then: 강제 취소는 409 예외 발생
        assertThat(cancelExceptionRef.get())
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

        // DB 재조회: COMPLETED 유지, checkedOutAt 기록, 슬롯 1건 보존, 토큰 폐기, AUTO_CHECK_OUT 이력, 강제 취소 감사 로그 0건
        newRequiresNewTx().executeWithoutResult({ _ ->
            val reloaded = reservationRepository.findById(reservationId).orElseThrow()
            assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.COMPLETED)
            assertThat(reloaded.checkedOutAt).isNotNull()
            assertThat(reloaded.cancelledAt).isNull()

            // 슬롯 1건 보존
            assertThat(reservationSlotRepository.count()).isEqualTo(1)

            // 토큰 폐기 확인 (AUTO_CHECK_OUT)
            val reloadedToken = doorAccessTokenRepository.findById(token.id!!).orElseThrow()
            assertThat(reloadedToken.isRevoked).isTrue()
            assertThat(reloadedToken.revokeReason).isEqualTo("AUTO_CHECK_OUT")

            // 상태 이력: AUTO_CHECK_OUT 1건
            val histories = statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId)
            assertThat(histories).hasSize(1)
            assertThat(histories.get(0).fromStatus).isEqualTo(ReservationStatus.IN_USE)
            assertThat(histories.get(0).toStatus).isEqualTo(ReservationStatus.COMPLETED)
            assertThat(histories.get(0).reason).isEqualTo("AUTO_CHECK_OUT")

            // 감사 로그: 강제 취소 롤백으로 0건
            assertThat(auditLogRepository.count()).isZero()
            // outbox: 강제 취소 롤백으로 0건
            assertThat(outboxEventRepository.count()).isZero()
        })
    }

    @Test
    @DisplayName("실제 경합 4 (결정론적): 최초 체크인이 CONFIRMED 스냅샷을 만든 뒤, 동시 최초 체크인과 토큰 재발급이 먼저 커밋되면 기존 토큰은 Locking Read로 TOKEN_REVOKED 거절된다")
    fun concurrency_checkInSnapshotFirst_reissueAndCheckInCommits_checkInAbortsWithTokenRevoked() {
        val start = TestClockConfig.BASE_TIME
        val end = start.plusHours(1)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.CONFIRMED)
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start.plusMinutes(30)))
                return@execute res.getId()
            })!!

        val tokenResp = doorAccessTokenService.issue(userMember.id!!, reservationId)
        val oldRawToken = tokenResp.token
        val oldToken = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow()

        val checkInSnapshotCreated = CountDownLatch(1)
        val reissueCommitted = CountDownLatch(1)

        // doorAccessTimePolicy 호출 시점에 latch를 걸어 checkIn 요청이 Read View를 연 뒤 멈추게 함
        doAnswer({ invocation ->
            checkInSnapshotCreated.countDown()
            assertThat(reissueCommitted.await(5, TimeUnit.SECONDS)).isTrue()
            return@doAnswer invocation.callRealMethod()
        }).`when`(doorAccessTimePolicy).findDenyReason(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull())

        val checkInResponseRef = AtomicReference<DoorAccessVerifyResponse>()
        val checkInExceptionRef = AtomicReference<Throwable>()

        // Thread B: 사용자 기존 토큰으로 최초 체크인 트랜잭션 시작 (스냅샷 생성 후 reissueCommitted 대기)
        val checkInFuture =
            executorService.submit(
                Runnable {
                    try {
                        newRequiresNewTx().execute({ _ ->
                            val resp =
                                doorAccessVerificationService.verify(
                                    userMember.id!!,
                                    DoorAccessVerifyRequest(space.id!!, oldRawToken),
                                )
                            checkInResponseRef.set(resp)
                            return@execute null
                        })
                    } catch (e: Throwable) {
                        checkInExceptionRef.set(e)
                    }
                },
            )

        assertThat(checkInSnapshotCreated.await(5, TimeUnit.SECONDS)).isTrue()

        // Thread A (메인 스레드): 동시 최초 체크인 커밋 (IN_USE) + 토큰 재발급 커밋 (기존 토큰 폐기)
        newRequiresNewTx().executeWithoutResult({ _ ->
            val updated =
                reservationRepository.checkInIfConfirmed(
                    reservationId,
                    TestClockConfig.BASE_TIME,
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.IN_USE,
                )
            assertThat(updated).isEqualTo(1)

            doorAccessTokenService.issue(userMember.id!!, reservationId)
        })

        reissueCommitted.countDown()
        checkInFuture.get(5, TimeUnit.SECONDS)

        // then: 불변식 단언
        // 1. 기존 토큰 요청은 최신 토큰 상태를 locking read하여 TOKEN_REVOKED 거절되어야 함
        val checkInResp = checkInResponseRef.get()
        assertThat(checkInResp).isNotNull()
        assertThat(checkInResp.result).isEqualTo(AccessResult.DENY)
        assertThat(checkInResp.reasonCode).isEqualTo(AccessDenyReason.TOKEN_REVOKED)

        // 2. DB 최종 상태 재조회
        newRequiresNewTx().executeWithoutResult({ _ ->
            val finalRes = reservationRepository.findById(reservationId).orElseThrow()
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.IN_USE)

            val reloadedOldToken = doorAccessTokenRepository.findById(oldToken.id!!).orElseThrow()
            assertThat(reloadedOldToken.isRevoked).isTrue()
            assertThat(reloadedOldToken.revokeReason).isEqualTo("REISSUED")

            // 출입 로그: ALLOW 로그 0건, DENY 로그 1건 (TOKEN_REVOKED)
            val logs = doorAccessLogRepository.findAll()
            assertThat(logs).filteredOn({ l -> l.result == AccessResult.ALLOW }).isEmpty()
            assertThat(logs)
                .filteredOn({ l ->
                    l.result == AccessResult.DENY &&
                        l.reasonCode == AccessDenyReason.TOKEN_REVOKED
                })
                .hasSize(1)
        })
    }

    @Test
    @DisplayName("실제 경합 5 (결정론적): 최초 체크인이 CONFIRMED 스냅샷을 만든 뒤, 다른 트랜잭션이 토큰만 폐기하고 CONFIRMED를 유지하면 체크인은 Locking Read로 TOKEN_REVOKED 거절된다")
    fun concurrency_checkInSnapshotFirst_tokenRevokedAndConfirmedKept_checkInAbortsWithTokenRevoked() {
        val start = TestClockConfig.BASE_TIME
        val end = start.plusHours(1)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.CONFIRMED)
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start.plusMinutes(30)))
                return@execute res.getId()
            })!!

        val tokenResp = doorAccessTokenService.issue(userMember.id!!, reservationId)
        val oldRawToken = tokenResp.token
        val oldToken = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow()

        val checkInSnapshotCreated = CountDownLatch(1)
        val tokenRevokeCommitted = CountDownLatch(1)

        // doorAccessTimePolicy 호출 시점에 latch를 걸어 checkIn 요청이 Read View를 연 뒤 멈추게 함
        doAnswer({ invocation ->
            checkInSnapshotCreated.countDown()
            assertThat(tokenRevokeCommitted.await(5, TimeUnit.SECONDS)).isTrue()
            return@doAnswer invocation.callRealMethod()
        }).`when`(doorAccessTimePolicy).findDenyReason(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull())

        val checkInResponseRef = AtomicReference<DoorAccessVerifyResponse>()
        val checkInExceptionRef = AtomicReference<Throwable>()

        // Thread B: 사용자 기존 토큰으로 최초 체크인 트랜잭션 시작 (스냅샷 생성 후 tokenRevokeCommitted 대기)
        val checkInFuture =
            executorService.submit(
                Runnable {
                    try {
                        newRequiresNewTx().execute({ _ ->
                            val resp =
                                doorAccessVerificationService.verify(
                                    userMember.id!!,
                                    DoorAccessVerifyRequest(space.id!!, oldRawToken),
                                )
                            checkInResponseRef.set(resp)
                            return@execute null
                        })
                    } catch (e: Throwable) {
                        checkInExceptionRef.set(e)
                    }
                },
            )

        assertThat(checkInSnapshotCreated.await(5, TimeUnit.SECONDS)).isTrue()

        // Thread A (메인 스레드): 사용자가 토큰을 직접 폐기 (예약 상태는 CONFIRMED 그대로 유지)
        newRequiresNewTx().executeWithoutResult({ _ ->
            doorAccessTokenService.revokeByReservationId(
                userMember.id!!,
                reservationId,
                TestClockConfig.BASE_TIME,
                "USER_CANCELLED_TOKEN",
            )
        })

        tokenRevokeCommitted.countDown()
        checkInFuture.get(5, TimeUnit.SECONDS)

        // then: 불변식 단언
        // 1. 체크인 요청은 토큰 Locking Read로 폐기를 감지하여 TOKEN_REVOKED 거절
        assertThat(checkInExceptionRef.get()).isNull()
        val checkInResp = checkInResponseRef.get()
        assertThat(checkInResp).isNotNull()
        assertThat(checkInResp.result).isEqualTo(AccessResult.DENY)
        assertThat(checkInResp.reasonCode).isEqualTo(AccessDenyReason.TOKEN_REVOKED)

        // 2. DB 최종 상태 재조회: 예약은 CONFIRMED 유지, 슬롯 보존, 이력 0건, ALLOW 로그 0건
        newRequiresNewTx().executeWithoutResult({ _ ->
            val finalRes = reservationRepository.findById(reservationId).orElseThrow()
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CONFIRMED)
            assertThat(finalRes.checkedInAt).isNull()

            // 슬롯 2건 온전히 보존
            assertThat(reservationSlotRepository.count()).isEqualTo(2)

            // 토큰 폐기 확인
            val reloadedOldToken = doorAccessTokenRepository.findById(oldToken.id!!).orElseThrow()
            assertThat(reloadedOldToken.isRevoked).isTrue()
            assertThat(reloadedOldToken.revokeReason).isEqualTo("USER_CANCELLED_TOKEN")

            // 상태 이력: 상태 전이가 실행되지 않았으므로 0건
            val histories = statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId)
            assertThat(histories).isEmpty()

            // 출입 로그: ALLOW 로그 0건, DENY 로그 1건 (TOKEN_REVOKED)
            val logs = doorAccessLogRepository.findAll()
            assertThat(logs).filteredOn({ l -> l.result == AccessResult.ALLOW }).isEmpty()
            assertThat(logs)
                .filteredOn({ l ->
                    l.result == AccessResult.DENY &&
                        l.reasonCode == AccessDenyReason.TOKEN_REVOKED
                })
                .hasSize(1)
        })
    }

    @Test
    @DisplayName("실제 경합 6 (결정론적): 재입장 요청이 IN_USE 스냅샷을 만든 뒤, 관리자 강제 취소가 먼저 커밋되면 재입장은 Locking Read로 RESERVATION_NOT_ACTIVE 거절된다")
    fun concurrency_reentrySnapshotFirst_forceCancelCommits_reentryAbortsWithReservationNotActive() {
        val start = TestClockConfig.BASE_TIME
        val end = start.plusHours(1)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.IN_USE)
                            .checkedInAt(start.minusMinutes(1))
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start.plusMinutes(30)))
                return@execute res.getId()
            })!!

        val tokenResp = doorAccessTokenService.issue(userMember.id!!, reservationId)
        val rawToken = tokenResp.token

        val reentrySnapshotCreated = CountDownLatch(1)
        val cancelCommitted = CountDownLatch(1)

        // doorAccessTimePolicy 호출 시점에 latch를 걸어 재입장 verify 요청이 IN_USE 스냅샷을 읽은 뒤 멈추게 함
        doAnswer({ invocation ->
            reentrySnapshotCreated.countDown()
            assertThat(cancelCommitted.await(5, TimeUnit.SECONDS)).isTrue()
            return@doAnswer invocation.callRealMethod()
        }).`when`(doorAccessTimePolicy).findDenyReason(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull())

        val reentryResponseRef = AtomicReference<DoorAccessVerifyResponse>()
        val reentryExceptionRef = AtomicReference<Throwable>()

        // Thread B: 사용자 재입장 트랜잭션 시작 (IN_USE 스냅샷 생성 후 cancelCommitted 대기)
        val reentryFuture =
            executorService.submit(
                Runnable {
                    try {
                        newRequiresNewTx().execute({ _ ->
                            val resp =
                                doorAccessVerificationService.verify(
                                    userMember.id!!,
                                    DoorAccessVerifyRequest(space.id!!, rawToken),
                                )
                            reentryResponseRef.set(resp)
                            return@execute null
                        })
                    } catch (e: Throwable) {
                        reentryExceptionRef.set(e)
                    }
                },
            )

        assertThat(reentrySnapshotCreated.await(5, TimeUnit.SECONDS)).isTrue()

        // Thread A (메인 스레드): 관리자 강제 취소 커밋 (CANCELLED, 슬롯 삭제, 토큰 폐기, 감사 로그)
        newRequiresNewTx().executeWithoutResult({ _ ->
            val cancelResp =
                adminReservationService.forceCancel(
                    reservationId,
                    "재입장 직전 관리자 강제 취소",
                    adminMember.id!!,
                )
            assertThat(cancelResp.status).isEqualTo(ReservationStatus.CANCELLED)
        })

        cancelCommitted.countDown()
        reentryFuture.get(5, TimeUnit.SECONDS)

        // then: 불변식 단언
        // 1. 재입장 요청은 최신 예약 Locking Read로 취소를 감지하여 RESERVATION_NOT_ACTIVE 거절
        assertThat(reentryExceptionRef.get()).isNull()
        val reentryResp = reentryResponseRef.get()
        assertThat(reentryResp).isNotNull()
        assertThat(reentryResp.result).isEqualTo(AccessResult.DENY)
        assertThat(reentryResp.reasonCode).isEqualTo(AccessDenyReason.RESERVATION_NOT_ACTIVE)

        // 2. DB 최종 상태 재조회: CANCELLED, 슬롯 0개, 추가 ALLOW 로그 0건 (최초 체크인 ALLOW 1건만 존재)
        newRequiresNewTx().executeWithoutResult({ _ ->
            val finalRes = reservationRepository.findById(reservationId).orElseThrow()
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CANCELLED)
            assertThat(reservationSlotRepository.count()).isZero()

            // 출입 로그: ALLOW 로그 0건, 재입장 거절 DENY 1건
            val logs = doorAccessLogRepository.findAll()
            assertThat(logs).filteredOn({ l -> l.result == AccessResult.ALLOW }).isEmpty()
            assertThat(logs)
                .filteredOn({ l ->
                    l.result == AccessResult.DENY &&
                        l.reasonCode == AccessDenyReason.RESERVATION_NOT_ACTIVE
                })
                .hasSize(1)

            // 상태 이력: FORCE_CANCEL 1건
            val histories = statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId)
            assertThat(histories).hasSize(1)
            assertThat(histories.get(0).fromStatus).isEqualTo(ReservationStatus.IN_USE)
            assertThat(histories.get(0).toStatus).isEqualTo(ReservationStatus.CANCELLED)

            // 감사 로그: 1건
            assertThat(auditLogRepository.count()).isEqualTo(1)
        })
    }

    @Test
    @DisplayName("실제 경합 7: 강제 취소 vs 최초 체크인 동시 출발 시 커밋 순서별 상태·로그·이력 정합성 불변식을 만족한다")
    fun concurrency_forceCancel_vs_firstCheckIn_concurrent() {
        // given: CONFIRMED 예약, 슬롯 2개, 도어 토큰 생성
        val start = TestClockConfig.BASE_TIME
        val end = start.plusHours(1)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.CONFIRMED)
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start.plusMinutes(30)))
                return@execute res.getId()
            })!!

        val tokenResp = doorAccessTokenService.issue(userMember.id!!, reservationId)
        val rawToken = tokenResp.token
        val token = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow()

        val readyLatch = CountDownLatch(2)
        val startLatch = CountDownLatch(1)

        val checkInResponseRef = AtomicReference<DoorAccessVerifyResponse>()
        val checkInExceptionRef = AtomicReference<Throwable>()
        val cancelResponseRef = AtomicReference<AdminReservationResponse>()
        val cancelExceptionRef = AtomicReference<Throwable>()

        // Thread A: 관리자 강제 취소
        val cancelFuture =
            executorService.submit(
                Runnable {
                    readyLatch.countDown()
                    try {
                        startLatch.await(5, TimeUnit.SECONDS)
                        val resp =
                            newRequiresNewTx().execute({ _ ->
                                adminReservationService.forceCancel(reservationId, "동시 강제 취소", adminMember.id!!)
                            })
                        cancelResponseRef.set(resp)
                    } catch (e: Throwable) {
                        cancelExceptionRef.set(e)
                    }
                },
            )

        // Thread B: 사용자 최초 체크인
        val checkInFuture =
            executorService.submit(
                Runnable {
                    readyLatch.countDown()
                    try {
                        startLatch.await(5, TimeUnit.SECONDS)
                        val resp =
                            newRequiresNewTx().execute({ _ ->
                                doorAccessVerificationService.verify(
                                    userMember.id!!,
                                    DoorAccessVerifyRequest(space.id!!, rawToken),
                                )
                            })
                        checkInResponseRef.set(resp)
                    } catch (e: Throwable) {
                        checkInExceptionRef.set(e)
                    }
                },
            )

        assertThat(readyLatch.await(3, TimeUnit.SECONDS)).isTrue()
        startLatch.countDown()

        cancelFuture.get(5, TimeUnit.SECONDS)
        checkInFuture.get(5, TimeUnit.SECONDS)

        // then: DB 상태의 일관성 및 선후관계 불변식 단언
        newRequiresNewTx().executeWithoutResult({ _ ->
            val finalRes = reservationRepository.findById(reservationId).orElseThrow()
            val finalSlots = reservationSlotRepository.findAll()
            val finalToken = doorAccessTokenRepository.findById(token.id!!).orElseThrow()
            val auditLogs = auditLogRepository.findAll()
            val histories = statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId)

            if (finalRes.getStatus() == ReservationStatus.CANCELLED) {
                // 강제 취소가 최종 반영된 경우:
                // 1) 강제 취소는 반드시 성공 응답을 받고 예외가 없어야 함
                assertThat(cancelResponseRef.get()).isNotNull()
                assertThat(cancelResponseRef.get().status).isEqualTo(ReservationStatus.CANCELLED)
                assertThat(cancelExceptionRef.get()).isNull()

                // 2) 체크인 스레드도 예외(락 타임아웃/데드락 등) 없이 정상 종료되어 응답 객체가 반드시 존재해야 함
                assertThat(checkInExceptionRef.get()).isNull()
                val checkInResp = checkInResponseRef.get()
                assertThat(checkInResp).isNotNull()

                // 공통 취소 상태 단언: 슬롯 0개, 토큰 폐기, 감사 로그 1건
                assertThat(finalSlots).isEmpty()
                assertThat(finalToken.isRevoked).isTrue()
                assertThat(auditLogs).hasSize(1)
                assertThat(auditLogs.get(0).action).isEqualTo(AuditAction.FORCE_CANCEL_RESERVATION)

                // 두 가지 정상 순서 분기 단언:
                if (checkInResp.result == AccessResult.ALLOW) {
                    // 분기 1: 체크인이 먼저 성공 커밋된 후, 관리자가 최신 IN_USE를 강제 취소한 경우
                    assertThat(doorAccessLogRepository.findAll()).filteredOn({ l -> l.result == AccessResult.ALLOW }).hasSize(1)
                    assertThat(histories).hasSize(2)
                    assertThat(histories.get(0).fromStatus).isEqualTo(ReservationStatus.CONFIRMED)
                    assertThat(histories.get(0).toStatus).isEqualTo(ReservationStatus.IN_USE)
                    assertThat(histories.get(0).reason).isEqualTo("FIRST_CHECK_IN")
                    assertThat(histories.get(1).fromStatus).isEqualTo(ReservationStatus.IN_USE)
                    assertThat(histories.get(1).toStatus).isEqualTo(ReservationStatus.CANCELLED)
                } else {
                    // 분기 2: 관리자 강제 취소가 먼저 커밋되어 체크인이 거절된 경우
                    assertThat(checkInResp.result).isEqualTo(AccessResult.DENY)
                    assertThat(checkInResp.reasonCode).isEqualTo(AccessDenyReason.RESERVATION_NOT_ACTIVE)
                    assertThat(doorAccessLogRepository.findAll()).filteredOn({ l -> l.result == AccessResult.ALLOW }).isEmpty()
                    assertThat(histories).hasSize(1)
                    assertThat(histories.get(0).fromStatus).isEqualTo(ReservationStatus.CONFIRMED)
                    assertThat(histories.get(0).toStatus).isEqualTo(ReservationStatus.CANCELLED)
                }
            } else if (finalRes.getStatus() == ReservationStatus.IN_USE) {
                // 체크인이 먼저 완료되고 강제 취소가 409 충돌로 거절된 경우:
                // 1) 체크인은 반드시 성공 응답을 받고 예외가 없어야 함
                assertThat(checkInExceptionRef.get()).isNull()
                val checkInResp = checkInResponseRef.get()
                assertThat(checkInResp).isNotNull()
                assertThat(checkInResp.result).isEqualTo(AccessResult.ALLOW)

                // 2) 강제 취소는 반드시 409 예외가 발생하고 응답이 없어야 함
                assertThat(cancelResponseRef.get()).isNull()
                assertThat(cancelExceptionRef.get())
                    .isInstanceOf(BusinessException::class.java)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

                // 공통 IN_USE 상태 단언
                assertThat(finalSlots).hasSize(2)
                assertThat(finalToken.isRevoked).isFalse()
                assertThat(auditLogs).isEmpty()
                assertThat(histories).hasSize(1)
                assertThat(histories.get(0).fromStatus).isEqualTo(ReservationStatus.CONFIRMED)
                assertThat(histories.get(0).toStatus).isEqualTo(ReservationStatus.IN_USE)
            } else {
                org.junit.jupiter.api.Assertions
                    .fail("예상치 못한 최종 상태: " + finalRes.getStatus())
            }
        })
    }

    @Test
    @DisplayName("실제 경합 8 (결정론적): 강제 취소가 Reservation UPDATE 잠금을 보유한 동안 재발급은 locking read 대기로 멈추며(회귀 방지), 취소 커밋 후 409 충돌 거절되고 활성 토큰이 0건이다")
    fun concurrency_forceCancelCommitsFirst_reissueAbortsWithConflictAndNoActiveToken() {
        val start = TestClockConfig.BASE_TIME
        val end = start.plusHours(1)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.CONFIRMED)
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start.plusMinutes(30)))
                return@execute res.getId()
            })!!

        // 초기 토큰 1 발급
        doorAccessTokenService.issue(userMember.id!!, reservationId)
        val oldToken = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow()

        // 초기 토큰 발급 시 호출된 invocation 기록 초기화
        org.mockito.Mockito.clearInvocations(doorAccessTokenService, doorAccessAuthorizationService, accessTokenGenerator)

        val cancelHoldingLock = CountDownLatch(1)
        val cancelCanCommit = CountDownLatch(1)
        val reissueStarted = CountDownLatch(1)

        val reissueResponseRef = AtomicReference<DoorAccessTokenResponse>()
        val reissueExceptionRef = AtomicReference<Throwable>()

        // 취소 트랜잭션이 forceCancelIfStatusIs로 Reservation X-Lock을 확보한 직후(revokeByReservation 시점)에 대기하도록 설정
        doAnswer({ invocation ->
            cancelHoldingLock.countDown()
            assertThat(cancelCanCommit.await(5, TimeUnit.SECONDS)).isTrue()
            return@doAnswer invocation.callRealMethod()
        }).`when`(doorAccessTokenService).revokeByReservation(eqNonNull(reservationId), anyNonNull(), anyNonNull())

        // 재발급 트랜잭션의 issue 진입 관찰
        doAnswer({ invocation ->
            reissueStarted.countDown()
            return@doAnswer invocation.callRealMethod()
        }).`when`(doorAccessTokenService).issue(eqNonNull(userMember.id!!), eqNonNull(reservationId))

        // Thread A: 관리자 강제 취소 시작 (Reservation 행 잠금 획득 후 revokeByReservation에서 대기)
        val cancelFuture =
            executorService.submit<Any?>({
                newRequiresNewTx().executeWithoutResult({ _ ->
                    val cancelResp =
                        adminReservationService.forceCancel(
                            reservationId,
                            "토큰 재발급 전 강제 취소",
                            adminMember.id!!,
                        )
                    assertThat(cancelResp.status).isEqualTo(ReservationStatus.CANCELLED)
                })
                return@submit null
            })

        // 취소 트랜잭션이 Reservation X-Lock을 확보했음을 확인
        assertThat(cancelHoldingLock.await(5, TimeUnit.SECONDS)).isTrue()

        // Thread B: 재발급 트랜잭션 시작 (취소가 커밋되지 않은 상태에서 issue 호출)
        val reissueFuture =
            executorService.submit(
                Runnable {
                    try {
                        newRequiresNewTx().execute({ _ ->
                            val resp = doorAccessTokenService.issue(userMember.id!!, reservationId)
                            reissueResponseRef.set(resp)
                            return@execute null
                        })
                    } catch (e: Throwable) {
                        reissueExceptionRef.set(e)
                    }
                },
            )

        // 재발급이 issue 호출에 진입했음을 확인
        assertThat(reissueStarted.await(5, TimeUnit.SECONDS)).isTrue()

        // [결정론적 회귀 방지 단언]:
        // 취소 트랜잭션이 아직 Reservation X-Lock을 쥐고 있으므로,
        // issue()의 첫 문장 findByIdForUpdate는 MySQL InnoDB 행 락 대기 상태에 묶여 있어야 한다.
        // 따라서 다음 단계인 validateOwner와 토큰 생성기 generate()는 취소 커밋 전까지 결코 호출되지 않아야 한다!
        // (만약 과거의 일반 findById로 회귀했다면 MVCC 스냅샷을 읽고 통과하여 이미 validateOwner와 generate를 불렀을 것이므로 실패함!)
        verify(doorAccessAuthorizationService, never()).validateOwner(anyNonNull(), anyNonNull())
        verify(accessTokenGenerator, never()).generate()

        // 이제 취소 트랜잭션의 커밋을 허용
        cancelCanCommit.countDown()
        cancelFuture.get(5, TimeUnit.SECONDS)

        // 취소가 커밋되면 재발급 스레드가 Reservation 락을 얻고, 최신 CANCELLED 상태를 감지하여 409 예외 발생
        reissueFuture.get(5, TimeUnit.SECONDS)

        // then: 불변식 단언
        // 1. 재발급 요청은 최신 CANCELLED 상태 감지로 RESERVATION_STATE_CONFLICT 발생
        assertThat(reissueResponseRef.get()).isNull()
        assertThat(reissueExceptionRef.get())
            .isNotNull()
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

        // 2. DB 최종 상태: CANCELLED, 슬롯 0개, 활성 토큰 0건, 이전 토큰 ADMIN_FORCE_CANCEL 폐기, 감사 1건
        newRequiresNewTx().executeWithoutResult({ _ ->
            val finalRes = reservationRepository.findById(reservationId).orElseThrow()
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CANCELLED)
            assertThat(reservationSlotRepository.count()).isZero()

            // 활성 토큰은 0개여야 함
            assertThat(doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId)).isEmpty()

            // 전체 토큰은 1개(초기 토큰)뿐이어야 하고 폐기 상태여야 함
            val tokens = doorAccessTokenRepository.findAll()
            assertThat(tokens).hasSize(1)
            assertThat(tokens.get(0).id!!).isEqualTo(oldToken.id!!)
            assertThat(tokens.get(0).isRevoked).isTrue()
            assertThat(tokens.get(0).revokeReason).isEqualTo("ADMIN_FORCE_CANCEL")

            // 감사 로그: 1건
            assertThat(auditLogRepository.count()).isEqualTo(1)
        })
    }

    @Test
    @DisplayName("실제 경합 9 (결정론적): 토큰 재발급이 먼저 커밋된 뒤 관리자 강제 취소가 실행되면 새로 재발급된 토큰까지 폐기되고 활성 토큰이 0건이다")
    fun concurrency_reissueCommitsFirst_forceCancelRevokesNewTokenSuccessfully() {
        val start = TestClockConfig.BASE_TIME
        val end = start.plusHours(1)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.CONFIRMED)
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start.plusMinutes(30)))
                return@execute res.getId()
            })!!

        // 1. 초기 토큰 1 발급
        doorAccessTokenService.issue(userMember.id!!, reservationId)
        val token1 = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow()

        // 2. 토큰 재발급 먼저 커밋: 토큰 1은 REISSUED로 폐기, 새 토큰 2 발급
        val token2Resp =
            newRequiresNewTx().execute({ _ ->
                doorAccessTokenService.issue(userMember.id!!, reservationId)
            })!!
        assertThat(token2Resp).isNotNull()

        // 3. 관리자 강제 취소 실행
        val cancelResp =
            newRequiresNewTx().execute({ _ ->
                adminReservationService.forceCancel(reservationId, "재발급 후 강제 취소", adminMember.id!!)
            })!!
        assertThat(cancelResp.status).isEqualTo(ReservationStatus.CANCELLED)

        // then: 불변식 단언
        newRequiresNewTx().executeWithoutResult({ _ ->
            val finalRes = reservationRepository.findById(reservationId).orElseThrow()
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.CANCELLED)
            assertThat(reservationSlotRepository.count()).isZero()

            // 활성 토큰은 반드시 0건이어야 함
            assertThat(doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId)).isEmpty()

            // 총 토큰 수는 2개이며, 둘 다 폐기 상태여야 함
            val allTokens = doorAccessTokenRepository.findAll()
            assertThat(allTokens).hasSize(2)

            val reloadedToken1 = doorAccessTokenRepository.findById(token1.id!!).orElseThrow()
            assertThat(reloadedToken1.isRevoked).isTrue()
            assertThat(reloadedToken1.revokeReason).isEqualTo("REISSUED")

            val reloadedToken2 =
                allTokens
                    .stream()
                    .filter({ t -> !t.id!!.equals(token1.id!!) })
                    .findFirst()
                    .orElseThrow()
            assertThat(reloadedToken2.isRevoked).isTrue()
            assertThat(reloadedToken2.revokeReason).isEqualTo("ADMIN_FORCE_CANCEL")

            // 감사 로그: 1건
            assertThat(auditLogRepository.count()).isEqualTo(1)
            // 상태 이력: 1건
            assertThat(statusHistoryRepository.count()).isEqualTo(1)
        })
    }

    @Test
    @DisplayName("실제 경합 10 (결정론적): 출입 검증이 Reservation 잠금을 보유한 동안 재발급은 Reservation 잠금에서 대기하며(Token 미진입), 역순 교착 없이 순차적으로 모두 완료된다")
    fun concurrency_verifyHoldsReservationLock_reissueWaitsWithoutDeadlock() {
        val start = TestClockConfig.BASE_TIME
        val end = start.plusHours(1)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.CONFIRMED)
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start.plusMinutes(30)))
                return@execute res.getId()
            })!!

        val initialTokenResp = doorAccessTokenService.issue(userMember.id!!, reservationId)
        val rawToken = initialTokenResp.token
        val initialToken = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow()

        // 초기 토큰 발급 시 호출된 invocation 기록 초기화
        org.mockito.Mockito.clearInvocations(doorAccessTokenService, doorAccessAuthorizationService, accessTokenGenerator)

        val verifyHoldingReservationLock = CountDownLatch(1)
        val verifyCanProceed = CountDownLatch(1)
        val reissueStarted = CountDownLatch(1)

        val verifyResponseRef = AtomicReference<DoorAccessVerifyResponse>()
        val verifyExceptionRef = AtomicReference<Throwable>()
        val reissueResponseRef = AtomicReference<DoorAccessTokenResponse>()
        val reissueExceptionRef = AtomicReference<Throwable>()

        // 재발급 트랜잭션의 issue 진입 관찰
        doAnswer({ invocation ->
            reissueStarted.countDown()
            return@doAnswer invocation.callRealMethod()
        }).`when`(doorAccessTokenService).issue(eqNonNull(userMember.id!!), eqNonNull(reservationId))

        // Thread A: 검증 트랜잭션이 선행 Reservation 잠금을 보유한 상태에서 재발급 대기를 관찰하고, 이후 검증을 정상 완료
        val verifyFuture =
            executorService.submit(
                Runnable {
                    try {
                        val resp =
                            newRequiresNewTx().execute({ _ ->
                                // 1. Reservation 비관적 락 선행 획득
                                reservationRepository.findByIdForUpdate(reservationId).orElseThrow()
                                verifyHoldingReservationLock.countDown()

                                // 2. Thread B(재발급)가 Reservation 잠금에 묶여 대기 중임을 외부에서 확인할 때까지 대기
                                try {
                                    assertThat(verifyCanProceed.await(5, TimeUnit.SECONDS)).isTrue()
                                } catch (e: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    throw RuntimeException(e)
                                }

                                // 3. 실제 출입 검증 완료 (동일 트랜잭션 내에서 ALLOW 판정)
                                return@execute doorAccessVerificationService.verify(
                                    userMember.id!!,
                                    DoorAccessVerifyRequest(space.id!!, rawToken),
                                )
                            })
                        verifyResponseRef.set(resp)
                    } catch (e: Throwable) {
                        verifyExceptionRef.set(e)
                    }
                },
            )

        // Thread A가 Reservation Locking Read를 마쳤음을 확인
        assertThat(verifyHoldingReservationLock.await(5, TimeUnit.SECONDS)).isTrue()

        // Thread B: 토큰 재발급 호출 (Reservation -> DoorAccessToken 순서이므로 Reservation 잠금에서 대기해야 함)
        val reissueFuture =
            executorService.submit(
                Runnable {
                    try {
                        val resp =
                            newRequiresNewTx().execute({ _ ->
                                doorAccessTokenService.issue(userMember.id!!, reservationId)
                            })
                        reissueResponseRef.set(resp)
                    } catch (e: Throwable) {
                        reissueExceptionRef.set(e)
                    }
                },
            )

        // Thread B가 재발급 issue 호출에 진입했음을 확인
        assertThat(reissueStarted.await(5, TimeUnit.SECONDS)).isTrue()

        // [결정론적 단언]:
        // Thread A가 Reservation 잠금을 보유 중이므로,
        // Thread B는 findByIdForUpdate에서 MySQL 행 락 대기 상태에 묶여 있다.
        // 따라서 다음 단계인 validateOwner와 토큰 생성기 generate()에는 아직 결코 도달하지 못했어야 한다!
        verify(doorAccessAuthorizationService, never()).validateOwner(anyNonNull(), anyNonNull())
        verify(accessTokenGenerator, never()).generate()

        // 이제 Thread A (검증)가 계속 진행하여 검증 커밋 및 Reservation 잠금을 해제하도록 허용
        verifyCanProceed.countDown()

        // 데드락(교착) 없이 두 작업 모두 10초 내 정상 완료되어야 함
        verifyFuture.get(10, TimeUnit.SECONDS)
        reissueFuture.get(10, TimeUnit.SECONDS)

        // then: 교착 없이 두 트랜잭션 모두 예외 없이 정상 종료
        assertThat(verifyExceptionRef.get()).isNull()
        assertThat(reissueExceptionRef.get()).isNull()

        assertThat(verifyResponseRef.get()).isNotNull()
        assertThat(verifyResponseRef.get().result).isEqualTo(AccessResult.ALLOW)

        assertThat(reissueResponseRef.get()).isNotNull()

        // DB 최종 상태 확인: 상태는 IN_USE, 활성 토큰 1개 존재
        newRequiresNewTx().executeWithoutResult({ _ ->
            val finalRes = reservationRepository.findById(reservationId).orElseThrow()
            assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.IN_USE)

            // 활성 토큰은 재발급된 새 토큰 1개
            val activeToken = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow()
            assertThat(activeToken.id!!).isNotEqualTo(initialToken.id!!)

            // 총 토큰 2개 (구 토큰 REISSUED, 신규 토큰 활성)
            val tokens = doorAccessTokenRepository.findAll()
            assertThat(tokens).hasSize(2)
        })
    }

    // ==========================================
    // 2. 순차 계약 테스트 (상태 정책 및 전이 계약 검증)
    // ==========================================

    @Test
    @DisplayName("순차 계약 1: 최초 체크인이 완전히 커밋되어 IN_USE인 예약은 관리자 강제 취소가 성공하며 CANCELLED, 슬롯 삭제, 토큰 폐기, 감사 로그가 남는다")
    fun contract_checkInCompleted_cancelSucceedsWithAdminAudit() {
        // given: CONFIRMED 예약 및 체크인 완료 (IN_USE)
        val start = TestClockConfig.BASE_TIME
        val end = start.plusHours(1)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.CONFIRMED)
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start.plusMinutes(30)))
                return@execute res.getId()
            })!!

        val tokenResp = doorAccessTokenService.issue(userMember.id!!, reservationId)
        val token = doorAccessTokenRepository.findByReservationIdAndRevokedAtIsNull(reservationId).orElseThrow()

        // 1. 체크인 실행 및 완료
        newRequiresNewTx().executeWithoutResult({ _ ->
            val resp =
                doorAccessVerificationService.verify(
                    userMember.id!!,
                    DoorAccessVerifyRequest(space.id!!, tokenResp.token),
                )
            assertThat(resp.result).isEqualTo(AccessResult.ALLOW)
        })

        // 2. IN_USE 상태에서 관리자 강제 취소 실행 -> 현재 서비스 정책상 정상 성공!
        newRequiresNewTx().executeWithoutResult({ _ ->
            val cancelResp =
                adminReservationService.forceCancel(
                    reservationId,
                    "체크인 후 강제 취소",
                    adminMember.id!!,
                )
            assertThat(cancelResp.status).isEqualTo(ReservationStatus.CANCELLED)
        })

        // 3. DB 최종 상태 단언: CANCELLED, 슬롯 삭제(0개), 토큰 폐기, IN_USE -> CANCELLED 이력, 감사 로그 1건
        newRequiresNewTx().executeWithoutResult({ _ ->
            val reloaded = reservationRepository.findById(reservationId).orElseThrow()
            assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CANCELLED)
            assertThat(reloaded.cancelledAt).isNotNull()
            assertThat(reservationSlotRepository.count()).isZero()

            val reloadedToken = doorAccessTokenRepository.findById(token.id!!).orElseThrow()
            assertThat(reloadedToken.isRevoked).isTrue()

            val histories = statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservationId)
            assertThat(histories).hasSize(2)
            assertThat(histories.get(0).reason).isEqualTo("FIRST_CHECK_IN")
            assertThat(histories.get(1).fromStatus).isEqualTo(ReservationStatus.IN_USE)
            assertThat(histories.get(1).toStatus).isEqualTo(ReservationStatus.CANCELLED)

            val auditLogs = auditLogRepository.findAll()
            assertThat(auditLogs).hasSize(1)
            assertThat(auditLogs.get(0).action).isEqualTo(AuditAction.FORCE_CANCEL_RESERVATION)
        })
    }

    @Test
    @DisplayName("순차 계약 2: 관리자 강제 취소가 이미 커밋되어 CANCELLED인 상태에서 강제 취소 재호출 시 RESERVATION_STATE_CONFLICT로 거절된다")
    fun contract_cancelCompleted_reCancelAbortsWithAlreadyCancelled() {
        val start = TestClockConfig.BASE_TIME.plusHours(1)
        val end = start.plusHours(2)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.CONFIRMED)
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                return@execute res.getId()
            })!!

        // 1. 1차 강제 취소 완료 커밋 (CONFIRMED -> CANCELLED)
        newRequiresNewTx().executeWithoutResult({ _ ->
            adminReservationService.forceCancel(reservationId, "1차 강제 취소", adminMember.id!!)
        })

        // 2. 이미 CANCELLED 상태인 예약에 대해 다시 forceCancel 호출 시 RESERVATION_STATE_CONFLICT 발생
        assertThatThrownBy({ adminReservationService.forceCancel(reservationId, "2차 강제 취소", adminMember.id!!) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

        // 3. 감사 로그는 1차 취소 1건만 존재
        newRequiresNewTx().executeWithoutResult({ _ ->
            assertThat(auditLogRepository.count()).isEqualTo(1)
        })
    }

    @Test
    @DisplayName("순차 계약 3: 관리자 강제 취소가 완전히 커밋되어 CANCELLED인 상태에서 사용자의 연장 시도는 거절되고 CANCELLED가 보존된다")
    fun contract_cancelCompleted_extendAbortsWithDisallowed() {
        val start = TestClockConfig.BASE_TIME.plusHours(1)
        val end = start.plusHours(1)

        val reservationId =
            newRequiresNewTx().execute({ _ ->
                val res =
                    reservationRepository.save(
                        Reservation
                            .builder()
                            .memberId(userMember.id!!)
                            .spaceId(space.id!!)
                            .startTime(start)
                            .endTime(end)
                            .pricePerSlotSnapshot(3000)
                            .totalAmount(6000)
                            .status(ReservationStatus.CONFIRMED)
                            .createdAt(start.minusDays(1))
                            .build(),
                    )

                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start))
                reservationSlotRepository.save(ReservationSlot.of(res.getId(), space.id!!, start.plusMinutes(30)))
                return@execute res.getId()
            })!!

        // 1. 관리자 강제 취소 완료 커밋 (CONFIRMED -> CANCELLED)
        newRequiresNewTx().executeWithoutResult({ _ ->
            adminReservationService.forceCancel(reservationId, "연장 전 강제 취소", adminMember.id!!)
        })

        // 2. 사용자가 연장을 시도하면 RESERVATION_EXTEND_NOT_ALLOWED 예외 발생
        val expectedEndTime = end
        val newEndTime = end.plusMinutes(30)

        assertThatThrownBy({ reservationExtendService.extend(userMember.id!!, reservationId, expectedEndTime, newEndTime) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_EXTEND_NOT_ALLOWED)

        // 3. DB 재조회: CANCELLED 상태 유지, 슬롯은 0개, 추가 차감 없음
        newRequiresNewTx().executeWithoutResult({ _ ->
            val reloaded = reservationRepository.findById(reservationId).orElseThrow()
            assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CANCELLED)
            assertThat(reservationSlotRepository.count()).isZero()

            val reloadedUser = memberRepository.findById(userMember.id!!).orElseThrow()
            assertThat(reloadedUser.balance).isEqualTo(100000 + 6000)
        })
    }
}
