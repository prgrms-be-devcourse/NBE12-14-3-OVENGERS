package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.dto.request.SpaceUpdateRequest
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.space.service.AdminSpaceService
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Import(SpaceReservationLockIntegrationTest.LockTestClockConfig::class)
class SpaceReservationLockIntegrationTest : IntegrationTestSupport() {
    @TestConfiguration(proxyBeanMethods = false)
    class LockTestClockConfig {
        companion object {
            val FIXED_DATE_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 15, 0)
            val FIXED_INSTANT: Instant = FIXED_DATE_TIME.atZone(ClockConfig.DEFAULT_ZONE).toInstant()
        }

        @Bean
        @Primary
        fun testClock(): Clock = Clock.fixed(FIXED_INSTANT, ClockConfig.DEFAULT_ZONE)
    }

    @Autowired
    private lateinit var adminSpaceService: AdminSpaceService

    @Autowired
    private lateinit var reservationHoldService: ReservationHoldService

    @Autowired
    private lateinit var reservationPaymentConfirmService: ReservationPaymentConfirmService

    @Autowired
    private lateinit var reservationExtendService: ReservationExtendService

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var idempotencyKeyRepository: com.ovengers.slotkey.global.idempotency.IdempotencyKeyRepository

    @Autowired
    private lateinit var creditTransactionRepository: com.ovengers.slotkey.credit.repository.CreditTransactionRepository

    @Autowired
    private lateinit var creditGrantService: com.ovengers.slotkey.credit.service.CreditGrantService

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var reservationSlotRepository: ReservationSlotRepository

    @Autowired
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private var memberId: Long = 0L
    private var spaceId: Long = 0L

    @BeforeEach
    fun setUp() {
        cleanup()

        val member =
            memberRepository.save(
                Member(
                    "locktest-" + System.nanoTime() + "@slotkey.test",
                    "테스트유저",
                ),
            )
        memberId = member.id!!

        val space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("잠금 테스트 회의실")
                    .location("테헤란로 100")
                    .capacity(6)
                    .pricePerSlot(2000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(22, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )
        spaceId = space.id!!
    }

    @AfterEach
    fun tearDown() {
        cleanup()
    }

    private fun cleanup() {
        auditLogRepository.deleteAllInBatch()
        idempotencyKeyRepository.deleteAllInBatch()
        creditTransactionRepository.deleteAllInBatch()
        reservationStatusHistoryRepository.deleteAllInBatch()
        reservationSlotRepository.deleteAllInBatch()
        reservationRepository.deleteAllInBatch()
        spaceRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
    }

    @Test
    @DisplayName("공간 수정 트랜잭션이 비관적 배타 락을 쥐고 있는 동안에는 HOLD 요청이 대기(직렬화)된다")
    fun spaceUpdate_locksHoldRequest() {
        val spaceLockAcquiredLatch = CountDownLatch(1)
        val holdAttemptStartedLatch = CountDownLatch(1)
        val releaseSpaceLockLatch = CountDownLatch(1)

        val executor = Executors.newFixedThreadPool(2)
        val holdFinishedBeforeSpaceRelease = AtomicBoolean(false)

        // Thread 1: AdminSpaceService.updateSpace 트랜잭션 (수동 트랜잭션으로 Lock 유지 시뮬레이션)
        val txTemplate = TransactionTemplate(transactionManager)
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)

        val spaceUpdateFuture =
            executor.submit(
                Runnable {
                    txTemplate.execute<Any?>({ _ ->
                        // 배타 락 획득
                        val space = spaceRepository.findByIdForUpdate(spaceId).orElseThrow()
                        spaceLockAcquiredLatch.countDown()

                        try {
                            // Thread 2가 HOLD를 시도할 시간을 줌
                            releaseSpaceLockLatch.await(5, TimeUnit.SECONDS)
                            space.updateDetail(
                                SpaceUpdateRequest(
                                    null,
                                    "이름변경",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                ),
                            )
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        return@execute null
                    })
                },
            )

        // Thread 1이 락을 잡을 때까지 대기
        assertThat(spaceLockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue()

        // Thread 2: ReservationHoldService.createHold 호출 (공유 락 획득 시도 -> 대기해야 함)
        val start =
            LocalDateTime
                .now(clock)
                .plusDays(1)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        val end = start.plusHours(2)

        val holdFuture =
            executor.submit<Any?>({
                holdAttemptStartedLatch.countDown()
                val hold = reservationHoldService.createHold(memberId, spaceId, start, end)
                if (releaseSpaceLockLatch.getCount() > 0) {
                    holdFinishedBeforeSpaceRelease.set(true)
                }
                return@submit hold
            })

        assertThat(holdAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue()
        // HOLD가 즉시 끝나지 않고 블록되어 있어야 함
        Thread.sleep(300)
        assertThat(holdFinishedBeforeSpaceRelease.get()).isFalse()

        // 공간 락 해제
        releaseSpaceLockLatch.countDown()

        spaceUpdateFuture.get(5, TimeUnit.SECONDS)
        val holdResult = holdFuture.get(5, TimeUnit.SECONDS)
        assertThat(holdResult).isNotNull()

        executor.shutdown()
    }

    @Test
    @DisplayName("시나리오 1: 관리자 배타 락 선행 -> HOLD 요청이 대기 후 변경된 새 운영시간으로 거절된다")
    fun concurrency_adminExclusiveLockFirst_thenHoldEvaluatesWithNewHours() {
        val adminLockAcquiredLatch = CountDownLatch(1)
        val holdAttemptStartedLatch = CountDownLatch(1)
        val releaseAdminLockLatch = CountDownLatch(1)

        val executor = Executors.newFixedThreadPool(2)
        val txTemplate = TransactionTemplate(transactionManager)
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)

        // Thread 1: 관리자가 운영시간을 11:00 ~ 22:00으로 축소 (배타 락 획득 후 대기)
        val adminFuture =
            executor.submit(
                Runnable {
                    txTemplate.execute<Any?>({ _ ->
                        val space = spaceRepository.findByIdForUpdate(spaceId).orElseThrow()
                        adminLockAcquiredLatch.countDown()
                        try {
                            releaseAdminLockLatch.await(5, TimeUnit.SECONDS)
                            space.updateDetail(
                                SpaceUpdateRequest(
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    LocalTime.of(11, 0),
                                    LocalTime.of(22, 0),
                                    null,
                                ),
                            )
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        return@execute null
                    })
                },
            )

        assertThat(adminLockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue()

        // Thread 2: 사용자가 10:00 ~ 11:00 HOLD 시도 (관리자 트랜잭션 종료 대기 후, 변경된 11:00 시작시간으로 인해
        // INVALID_RESERVATION_TIME 실패)
        val start =
            LocalDateTime
                .now(clock)
                .plusDays(1)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        val end = start.plusHours(1)

        val holdBlocked = AtomicBoolean(true)
        val holdFuture =
            executor.submit<Any?>({
                holdAttemptStartedLatch.countDown()
                try {
                    reservationHoldService.createHold(memberId, spaceId, start, end)
                    holdBlocked.set(false)
                    return@submit null
                } catch (e: Exception) {
                    holdBlocked.set(false)
                    return@submit e
                }
            })

        assertThat(holdAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue()
        Thread.sleep(300)
        assertThat(holdBlocked.get()).isTrue() // 아직 락 대기 중이어야 함

        releaseAdminLockLatch.countDown()
        adminFuture.get(5, TimeUnit.SECONDS)

        val holdException = holdFuture.get(5, TimeUnit.SECONDS)
        assertThat(holdException).isInstanceOf(BusinessException::class.java)
        assertThat((holdException as BusinessException).errorCode).isEqualTo(ErrorCode.INVALID_RESERVATION_TIME)

        executor.shutdown()
    }

    @Test
    @DisplayName("시나리오 2: HOLD/연장 공유 락 선행 -> 관리자 축소가 대기 후 새 점유를 보고 409 거절된다")
    fun concurrency_holdSharedLockFirst_thenAdminShrinkFailsWithConflict() {
        val holdLockAcquiredLatch = CountDownLatch(1)
        val adminAttemptStartedLatch = CountDownLatch(1)
        val releaseHoldLockLatch = CountDownLatch(1)

        val executor = Executors.newFixedThreadPool(2)
        val txTemplate = TransactionTemplate(transactionManager)
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)

        val start =
            LocalDateTime
                .now(clock)
                .plusDays(1)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        val end = start.plusHours(1)

        // Thread 1: HOLD 생성 트랜잭션 (Space 공유 락 획득 후 슬롯 저장하고 대기)
        val holdFuture =
            executor.submit(
                Runnable {
                    txTemplate.execute<Any?>({ _ ->
                        reservationHoldService.createHold(memberId, spaceId, start, end)
                        holdLockAcquiredLatch.countDown()
                        try {
                            releaseHoldLockLatch.await(5, TimeUnit.SECONDS)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        return@execute null
                    })
                },
            )

        assertThat(holdLockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue()

        // Thread 2: 관리자가 11:00 ~ 22:00으로 운영시간 축소 시도 (공유 락 때문에 배타 락 대기)
        val adminBlocked = AtomicBoolean(true)
        val adminFuture =
            executor.submit<Any?>({
                adminAttemptStartedLatch.countDown()
                try {
                    adminSpaceService.updateSpace(
                        spaceId,
                        SpaceUpdateRequest(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            LocalTime.of(11, 0),
                            LocalTime.of(22, 0),
                            null,
                        ),
                        100L,
                    )
                    adminBlocked.set(false)
                    return@submit null
                } catch (e: Exception) {
                    adminBlocked.set(false)
                    return@submit e
                }
            })

        assertThat(adminAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue()
        Thread.sleep(300)
        assertThat(adminBlocked.get()).isTrue() // 관리자가 락 대기 중이어야 함

        releaseHoldLockLatch.countDown()
        holdFuture.get(5, TimeUnit.SECONDS)

        // HOLD가 커밋된 후 관리자는 최신 점유 슬롯(10:00~11:00)을 발견하고 409 예외 발생
        val adminException = adminFuture.get(5, TimeUnit.SECONDS)
        assertThat(adminException).isInstanceOf(BusinessException::class.java)
        assertThat((adminException as BusinessException).errorCode)
            .isEqualTo(ErrorCode.SPACE_OPERATING_HOURS_CONFLICT)

        executor.shutdown()
    }

    @Test
    @DisplayName("시나리오 3: 가격 수정 선행 -> 결제 시도가 대기 후 SPACE_VERSION_MISMATCH 예외가 발생한다")
    fun concurrency_priceUpdateFirst_thenPaymentFailsWithVersionMismatch() {
        // 먼저 2000원(version 0) 시점에 HOLD 생성 및 회원 크레딧 100,000 충전
        creditGrantService.grantSignupCredit(memberId, 100000)
        val start =
            LocalDateTime
                .now(clock)
                .plusDays(1)
                .withHour(14)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        val end = start.plusHours(1)
        val hold = reservationHoldService.createHold(memberId, spaceId, start, end)
        assertThat(hold.spaceVersion).isEqualTo(0)

        val adminPriceLockLatch = CountDownLatch(1)
        val payAttemptStartedLatch = CountDownLatch(1)
        val releaseAdminPriceLockLatch = CountDownLatch(1)

        val executor = Executors.newFixedThreadPool(2)
        val txTemplate = TransactionTemplate(transactionManager)
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)

        // Thread 1: 관리자가 가격을 3000원으로 변경 (version 0 -> 1 증가, 커밋 전까지 락 유지)
        val adminFuture =
            executor.submit(
                Runnable {
                    txTemplate.execute<Any?>({ _ ->
                        val space = spaceRepository.findByIdForUpdate(spaceId).orElseThrow()
                        adminPriceLockLatch.countDown()
                        try {
                            releaseAdminPriceLockLatch.await(5, TimeUnit.SECONDS)
                            space.updateDetail(
                                SpaceUpdateRequest(
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    3000L,
                                    null,
                                    null,
                                    null,
                                    null,
                                ),
                            )
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        return@execute null
                    })
                },
            )

        assertThat(adminPriceLockLatch.await(3, TimeUnit.SECONDS)).isTrue()

        // Thread 2: 사용자가 기존 version 0으로 결제 확인 시도 -> Space 공유 락 대기 후 version 1을 읽고
        // SPACE_VERSION_MISMATCH
        val payBlocked = AtomicBoolean(true)
        val payFuture =
            executor.submit<Any?>({
                payAttemptStartedLatch.countDown()
                try {
                    reservationPaymentConfirmService.confirm(memberId, hold.reservationId, 0, "idemp-ver-mismatch")
                    payBlocked.set(false)
                    return@submit null
                } catch (e: Exception) {
                    payBlocked.set(false)
                    return@submit e
                }
            })

        assertThat(payAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue()
        Thread.sleep(300)
        assertThat(payBlocked.get()).isTrue()

        releaseAdminPriceLockLatch.countDown()
        adminFuture.get(5, TimeUnit.SECONDS)

        val payException = payFuture.get(5, TimeUnit.SECONDS)
        assertThat(payException).isInstanceOf(BusinessException::class.java)
        assertThat((payException as BusinessException).errorCode).isEqualTo(ErrorCode.SPACE_VERSION_MISMATCH)

        executor.shutdown()
    }

    @Test
    @DisplayName("시나리오 4: 결제 공유 락 선행 -> 기존 HOLD 가격으로 확정된 뒤 가격 수정이 완료된다")
    fun concurrency_paymentSharedLockFirst_thenConfirmedAtOldPriceBeforePriceUpdate() {
        creditGrantService.grantSignupCredit(memberId, 100000)
        val start =
            LocalDateTime
                .now(clock)
                .plusDays(1)
                .withHour(15)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        val end = start.plusHours(1)
        val hold = reservationHoldService.createHold(memberId, spaceId, start, end)

        val payLockAcquiredLatch = CountDownLatch(1)
        val adminAttemptStartedLatch = CountDownLatch(1)
        val releasePayLockLatch = CountDownLatch(1)

        val executor = Executors.newFixedThreadPool(2)
        val txTemplate = TransactionTemplate(transactionManager)
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)

        val confirmedRef =
            java.util.concurrent.atomic
                .AtomicReference<ReservationResponse>()
        val payFuture =
            executor.submit(
                Runnable {
                    txTemplate.execute<Any?>({ _ ->
                        val confirmed =
                            reservationPaymentConfirmService.confirm(
                                memberId,
                                hold.reservationId,
                                0,
                                "idemp-pay-first",
                            )
                        confirmedRef.set(confirmed)
                        payLockAcquiredLatch.countDown()
                        try {
                            releasePayLockLatch.await(5, TimeUnit.SECONDS)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        return@execute null
                    })
                },
            )

        assertThat(payLockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue()

        // Thread 2: 관리자가 가격을 3000원으로 변경 시도 (Space 공유 락 때문에 배타 락 대기)
        val adminBlocked = AtomicBoolean(true)
        val adminFuture =
            executor.submit<Any?>({
                adminAttemptStartedLatch.countDown()
                try {
                    adminSpaceService.updateSpace(
                        spaceId,
                        SpaceUpdateRequest(
                            null,
                            null,
                            null,
                            null,
                            null,
                            3000L,
                            null,
                            null,
                            null,
                            null,
                        ),
                        100L,
                    )
                    adminBlocked.set(false)
                    return@submit null
                } catch (e: Exception) {
                    adminBlocked.set(false)
                    return@submit e
                }
            })

        assertThat(adminAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue()
        Thread.sleep(300)
        assertThat(adminBlocked.get()).isTrue()

        // 결제 락 해제 -> 결제 커밋 -> 관리자 가격 수정 완료
        releasePayLockLatch.countDown()
        payFuture.get(5, TimeUnit.SECONDS)
        assertThat(confirmedRef.get()).isNotNull()

        adminFuture.get(5, TimeUnit.SECONDS)

        // 검증: 예약은 기존 단가(2000원)로 CONFIRMED 완료되었고, 공간은 새 가격(3000원, version 1)으로 변경됨
        val reservation = reservationRepository.findById(hold.reservationId).orElseThrow()
        assertThat(reservation.getStatus())
            .isEqualTo(com.ovengers.slotkey.reservation.entity.ReservationStatus.CONFIRMED)
        assertThat(reservation.getPricePerSlotSnapshot()).isEqualTo(2000)
        assertThat(reservation.getTotalAmount()).isEqualTo(4000)

        val updatedSpace = spaceRepository.findById(spaceId).orElseThrow()
        assertThat(updatedSpace.pricePerSlot).isEqualTo(3000L)
        assertThat(updatedSpace.version).isEqualTo(1)

        executor.shutdown()
    }

    @Test
    @DisplayName("미래 유효 슬롯이 축소 대상 범위에 걸치면 409 SPACE_OPERATING_HOURS_CONFLICT 가 발생한다")
    fun updateSpace_conflictWithFutureSlot_throwsConflict() {
        // 내일 날짜로 고정된 공간 예약 설정: 09:30 ~ 10:30 슬롯
        val tomorrow = LocalDateTime.now(clock).plusDays(1)
        val start =
            tomorrow
                .withHour(9)
                .withMinute(30)
                .withSecond(0)
                .withNano(0)
        val end =
            tomorrow
                .withHour(10)
                .withMinute(30)
                .withSecond(0)
                .withNano(0)
        reservationHoldService.createHold(memberId, spaceId, start, end)

        // 관리자가 운영 시작 시각을 10:00으로 늦추려고 시도
        // 09:30~10:00 슬롯이 새 운영시간(10:00~) 밖이므로 충돌 발생해야 함
        val shrinkRequest =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalTime.of(10, 0),
                LocalTime.of(22, 0),
                null,
            )

        assertThatThrownBy({ adminSpaceService.updateSpace(spaceId, shrinkRequest, 100L) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_OPERATING_HOURS_CONFLICT)

        val unmodified = spaceRepository.findById(spaceId).orElseThrow()
        assertThat(unmodified.openingTime).isEqualTo(LocalTime.of(9, 0))
    }

    @Test
    @DisplayName("실제 현재 진행 중인 슬롯(slotStart < now < slotEnd)이 축소 대상 범위에 걸치면 409가 발생하고 Space 및 AuditLog가 변경되지 않는다")
    fun updateSpace_conflictWithOngoingSlot_throwsConflictAndRollsBack() {
        val now = LocalDateTime.now(clock)
        // 고정 Clock 기준 (now = 10:15:00): slotStart(10:00) < now(10:15) < slotEnd(10:30) 명시적 구성
        val slotStart = now.toLocalDate().atTime(10, 0)
        val slotEnd = now.toLocalDate().atTime(10, 30)
        assertThat(slotStart).isBefore(now)
        assertThat(now).isBefore(slotEnd)

        val running =
            reservationRepository.save(
                Reservation
                    .builder()
                    .memberId(memberId)
                    .spaceId(spaceId)
                    .startTime(slotStart)
                    .endTime(slotEnd)
                    .status(com.ovengers.slotkey.reservation.entity.ReservationStatus.IN_USE)
                    .pricePerSlotSnapshot(2000)
                    .totalAmount(2000)
                    .createdAt(slotStart.minusMinutes(30))
                    .checkedInAt(slotStart)
                    .build(),
            )
        reservationSlotRepository.save(
            com.ovengers.slotkey.reservation.entity.ReservationSlot.of(
                running.getId(),
                spaceId,
                slotStart,
            ),
        )

        val initialAuditCount = auditLogRepository.count()

        // 운영 시작 시간을 slotStart 이후인 10:30으로 축소 시도 (기존 09:00 -> 10:30)
        val newOpeningTime = LocalTime.of(10, 30)
        val shrinkRequest =
            SpaceUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                newOpeningTime,
                LocalTime.of(22, 0),
                null,
            )

        assertThatThrownBy({ adminSpaceService.updateSpace(spaceId, shrinkRequest, 100L) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SPACE_OPERATING_HOURS_CONFLICT)

        // 롤백 확인: Space 운영시간 및 감사로그 변경 없음
        val unmodified = spaceRepository.findById(spaceId).orElseThrow()
        assertThat(unmodified.openingTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(auditLogRepository.count()).isEqualTo(initialAuditCount)
    }

    @Test
    @DisplayName("연장 트랜잭션이 Space 공유 락 및 Reservation 배타 락을 획득하면 관리자 운영시간 축소 및 반대 Reservation 갱신이 블록되고 커밋 후 최종 반영된다")
    fun concurrency_extendLocksSpaceAndReservation_blocksConflictingUpdates() {
        // 크레딧 50000 충전 및 확정된 예약 생성 (11:00 ~ 13:00)
        creditGrantService.grantAdminCredit(memberId, 50000, "연장 동시성 테스트 충전")

        val tomorrow = LocalDateTime.now(clock).plusDays(1)
        val start =
            tomorrow
                .withHour(11)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        val end =
            tomorrow
                .withHour(13)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        val newEnd =
            tomorrow
                .withHour(14)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)

        val hold = reservationHoldService.createHold(memberId, spaceId, start, end)
        reservationPaymentConfirmService.confirm(memberId, hold.reservationId, 0, "idemp-extend-concurrency")

        val extendLockAcquiredLatch = CountDownLatch(1)
        val secondTxAttemptStartedLatch = CountDownLatch(1)
        val secondTxPassedSpaceLockLatch = CountDownLatch(1)
        val adminAttemptStartedLatch = CountDownLatch(1)
        val releaseExtendLockLatch = CountDownLatch(1)

        val executor = Executors.newFixedThreadPool(3)
        val txTemplate = TransactionTemplate(transactionManager)
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)

        try {
            // Thread 1: 연장 트랜잭션 (Space 공유 락 및 Reservation 배타 락 획득 후 대기)
            val extendResultRef =
                java.util.concurrent.atomic
                    .AtomicReference<ReservationResponse>()
            val extendFuture =
                executor.submit(
                    Runnable {
                        txTemplate.execute<Any?>({ _ ->
                            val extended =
                                reservationExtendService.extend(
                                    memberId,
                                    hold.reservationId,
                                    end,
                                    newEnd,
                                )
                            extendResultRef.set(extended)
                            extendLockAcquiredLatch.countDown()
                            try {
                                releaseExtendLockLatch.await(5, TimeUnit.SECONDS)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            return@execute null
                        })
                    },
                )

            assertThat(extendLockAcquiredLatch.await(3, TimeUnit.SECONDS)).isTrue()

            // Thread 2: 동일 Reservation에 대한 반대 연장 시도 (13:00 기준 15:00 연장 시도)
            // Space 공유 락을 통과한 뒤, 동일 Reservation 배타 락(findByIdForUpdate)에서 블록되어야 함
            val secondTxBlocked = AtomicBoolean(true)
            val secondExtendFuture =
                executor.submit<Any?>({
                    secondTxAttemptStartedLatch.countDown()
                    try {
                        return@submit txTemplate.execute({ _ ->
                            // 잠금 순서에 따라 Space 공유 락 먼저 통과 확인
                            val targetSpaceId = reservationRepository.findSpaceIdById(hold.reservationId)
                            spaceRepository.findByIdForShare(targetSpaceId!!).orElseThrow()
                            secondTxPassedSpaceLockLatch.countDown()

                            // 동일 예약 연장 시도 -> 내부의 findByIdForUpdate에서 Thread 1 커밋 전까지 블록됨
                            val result =
                                reservationExtendService.extend(
                                    memberId,
                                    hold.reservationId,
                                    end,
                                    newEnd.plusHours(1),
                                )
                            secondTxBlocked.set(false)
                            return@execute result
                        })
                    } catch (e: Exception) {
                        secondTxBlocked.set(false)
                        return@submit e
                    }
                })

            // Thread 2가 Space 공유 락을 정상 획득했음을 확인
            assertThat(secondTxAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(secondTxPassedSpaceLockLatch.await(3, TimeUnit.SECONDS)).isTrue()

            // Thread 3: 관리자가 운영시간을 13:30까지로 축소 시도
            // Space 공유 락(Thread 1, 2)으로 인해 Space 배타 락에서 대기 후 충돌 감지되어 409 실패해야 함
            val adminBlocked = AtomicBoolean(true)
            val adminFuture =
                executor.submit<Any?>({
                    adminAttemptStartedLatch.countDown()
                    try {
                        adminSpaceService.updateSpace(
                            spaceId,
                            SpaceUpdateRequest(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                LocalTime.of(13, 30),
                                null,
                            ),
                            100L,
                        )
                        adminBlocked.set(false)
                        return@submit null
                    } catch (e: Exception) {
                        adminBlocked.set(false)
                        return@submit e
                    }
                })

            assertThat(adminAttemptStartedLatch.await(3, TimeUnit.SECONDS)).isTrue()
            Thread.sleep(300)

            // Thread 2는 Space 공유 락 통과 후 Reservation 배타 락에서 대기 중이어야 함
            assertThat(secondTxBlocked.get()).isTrue()
            assertThat(secondExtendFuture.isDone()).isFalse()

            // Thread 3은 Space 배타 락에서 대기 중이어야 함
            assertThat(adminBlocked.get()).isTrue()
            assertThat(adminFuture.isDone()).isFalse()

            // 연장 트랜잭션(Thread 1) 락 해제 및 커밋
            releaseExtendLockLatch.countDown()
            extendFuture.get(5, TimeUnit.SECONDS)
            assertThat(extendResultRef.get()).isNotNull()

            // Thread 2는 Reservation 배타 락 대기 해제 후, 예약 정보가 14:00으로 변경되었으므로 RESERVATION_STATE_CONFLICT 발생
            val secondResult = secondExtendFuture.get(5, TimeUnit.SECONDS)
            assertThat(secondResult).isInstanceOf(BusinessException::class.java)
            assertThat((secondResult as BusinessException).errorCode)
                .isEqualTo(ErrorCode.RESERVATION_STATE_CONFLICT)

            // Thread 3 관리자 트랜잭션은 Space 락 해제 후 커밋된 13:00~14:00 슬롯을 감지하여 409 SPACE_OPERATING_HOURS_CONFLICT 발생
            val adminResult = adminFuture.get(5, TimeUnit.SECONDS)
            assertThat(adminResult).isInstanceOf(BusinessException::class.java)
            assertThat((adminResult as BusinessException).errorCode)
                .isEqualTo(ErrorCode.SPACE_OPERATING_HOURS_CONFLICT)

            // DB 최종 상태 검증
            val finalReservation = reservationRepository.findById(hold.reservationId).orElseThrow()
            assertThat(finalReservation.getEndTime()).isEqualTo(newEnd)
            assertThat(finalReservation.getStatus())
                .isEqualTo(com.ovengers.slotkey.reservation.entity.ReservationStatus.CONFIRMED)
            // 11:00~13:00 (4슬롯 * 2000원 = 8000원) + Thread 1 연장 13:00~14:00 (2슬롯 * 2000원 = 4000원) = 12000원
            assertThat(finalReservation.getTotalAmount()).isEqualTo(12000)

            // 슬롯: 11:00부터 14:00까지 정확히 6개 슬롯 존재 확인 (Thread 2 롤백으로 14:00~15:00 슬롯 미생성)
            val finalSlots = reservationSlotRepository.findAllByReservationId(hold.reservationId)
            assertThat(finalSlots).hasSize(6)

            // 크레딧: 50,000 - 8,000(초기결제) - 4,000(Thread 1 연장) = 38,000원 (Thread 2 롤백으로 추가 차감 없음)
            val member = memberRepository.findById(memberId).orElseThrow()
            assertThat(member.balance).isEqualTo(38000)

            // 공간: 운영시간 22:00 유지 (Thread 3 롤백)
            val unmodifiedSpace = spaceRepository.findById(spaceId).orElseThrow()
            assertThat(unmodifiedSpace.closingTime).isEqualTo(LocalTime.of(22, 0))
        } finally {
            executor.shutdownNow()
        }
    }
}
