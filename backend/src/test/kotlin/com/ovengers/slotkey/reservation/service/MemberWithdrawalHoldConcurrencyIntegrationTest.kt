package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.auth.entity.LocalCredential
import com.ovengers.slotkey.auth.repository.LocalCredentialRepository
import com.ovengers.slotkey.auth.repository.RefreshTokenRepository
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.idempotency.IdempotencyKeyRepository
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.member.service.MemberWithdrawalService
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
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
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@Import(MemberWithdrawalHoldConcurrencyIntegrationTest.TestClockConfig::class)
class MemberWithdrawalHoldConcurrencyIntegrationTest : IntegrationTestSupport() {
    @TestConfiguration(proxyBeanMethods = false)
    class TestClockConfig {
        companion object {
            val FIXED_DATE_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)
            val FIXED_INSTANT: Instant = FIXED_DATE_TIME.atZone(ClockConfig.DEFAULT_ZONE).toInstant()
        }

        @Bean
        @Primary
        fun testClock(): Clock = Clock.fixed(FIXED_INSTANT, ClockConfig.DEFAULT_ZONE)
    }

    @Autowired
    private lateinit var reservationHoldService: ReservationHoldService

    @Autowired
    private lateinit var memberWithdrawalService: MemberWithdrawalService

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
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Autowired
    private lateinit var idempotencyKeyRepository: IdempotencyKeyRepository

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    private lateinit var doorAccessTokenRepository: DoorAccessTokenRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var localCredentialRepository: LocalCredentialRepository

    private var memberId: Long = 0L
    private var spaceId: Long = 0L

    private companion object {
        const val RAW_PASSWORD = "testPassword123!"
    }

    @BeforeEach
    fun setUp() {
        cleanup()

        val transactionTemplate = TransactionTemplate(transactionManager)

        memberId =
            transactionTemplate.execute({ _ ->
                val member =
                    memberRepository.saveAndFlush(
                        Member(
                            "withdraw-hold-" + System.nanoTime() + "@slotkey.test",
                            "탈퇴테스트유저",
                        ),
                    )

                localCredentialRepository.saveAndFlush(
                    LocalCredential(
                        member,
                        passwordEncoder.encode(RAW_PASSWORD),
                        LocalDateTime.now(clock),
                    ),
                )

                return@execute member.id!!
            })!!

        val space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("경합 테스트 회의실")
                    .location("테헤란로 200")
                    .capacity(4)
                    .pricePerSlot(2500L)
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
        doorAccessTokenRepository.deleteAllInBatch()
        refreshTokenRepository.deleteAllInBatch()
        auditLogRepository.deleteAllInBatch()
        idempotencyKeyRepository.deleteAllInBatch()
        creditTransactionRepository.deleteAllInBatch()
        reservationStatusHistoryRepository.deleteAllInBatch()
        reservationSlotRepository.deleteAllInBatch()
        reservationRepository.deleteAllInBatch()
        spaceRepository.deleteAllInBatch()

        // 회원을 참조하는 인증 정보를 먼저 삭제
        localCredentialRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()
    }

    @Test
    @DisplayName("HOLD 선행 시나리오: HOLD가 먼저 회원 잠금을 확보하면 탈퇴는 대기 후 WITHDRAWAL_ACTIVE_RESERVATION으로 거절된다")
    fun holdPreceding_withdrawalBlockedAndFailsWithActiveReservation() {
        val start = LocalDateTime.of(2026, 9, 20, 14, 0)
        val end = LocalDateTime.of(2026, 9, 20, 15, 0)

        val holdCreatedLatch = CountDownLatch(1)
        val allowHoldCommitLatch = CountDownLatch(1)

        val transactionTemplate = TransactionTemplate(transactionManager)
        val executor = Executors.newFixedThreadPool(2)

        try {
            // Thread 1: HOLD 생성 후 트랜잭션 커밋 전 대기 (회원 배타 잠금 유지)
            val holdFuture =
                executor.submit<ReservationResponse?>({
                    transactionTemplate.execute({ _ ->
                        val response = reservationHoldService.createHold(memberId, spaceId, start, end)
                        holdCreatedLatch.countDown()
                        try {
                            val released = allowHoldCommitLatch.await(10, TimeUnit.SECONDS)
                            if (!released) {
                                throw IllegalStateException("allowHoldCommitLatch timed out")
                            }
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw RuntimeException(e)
                        }
                        return@execute response
                    })
                })

            // HOLD가 회원 잠금을 잡을 때까지 대기
            assertThat(holdCreatedLatch.await(5, TimeUnit.SECONDS)).isTrue()

            // 탈퇴 스레드가 실제 findByIdForUpdate에 진입함을 관측하기 위한 프록시 설정
            val withdrawEnteredLatch = CountDownLatch(1)
            val originalRepo = ReflectionTestUtils.getField(memberWithdrawalService, "memberRepository") as MemberRepository
            val proxyRepo =
                Proxy.newProxyInstance(
                    MemberRepository::class.java.getClassLoader(),
                    arrayOf(MemberRepository::class.java),
                    { _, method, args ->
                        if ("findByIdForUpdate".equals(method.getName()) &&
                            (memberId == args!![0])
                        ) {
                            withdrawEnteredLatch.countDown()
                        }
                        try {
                            return@newProxyInstance method.invoke(originalRepo, *args.orEmpty())
                        } catch (e: InvocationTargetException) {
                            throw e.cause!!
                        }
                    },
                ) as MemberRepository
            ReflectionTestUtils.setField(memberWithdrawalService, "memberRepository", proxyRepo)

            val withdrawFuture: Future<*>
            try {
                // Thread 2: 탈퇴 요청 시작 -> Member X 락 대기에 진입
                withdrawFuture = executor.submit(Runnable { memberWithdrawalService.withdraw(memberId, RAW_PASSWORD) })

                // 탈퇴 스레드가 실제 findByIdForUpdate 저장소 잠금 호출에 진입했음을 확인
                assertThat(withdrawEnteredLatch.await(5, TimeUnit.SECONDS)).isTrue()
                // 선행 HOLD 트랜잭션이 커밋되기 전까지 후행 탈퇴 작업이 완료되지 못함을 입증
                assertThat(withdrawFuture.isDone()).isFalse()

                // HOLD 커밋 허용 -> 잠금 해제
                allowHoldCommitLatch.countDown()

                // HOLD 성공 확인
                val holdResponse = holdFuture.get(10, TimeUnit.SECONDS)
                assertThat(holdResponse).isNotNull()
                assertThat(holdResponse!!.status).isEqualTo("HELD")

                // 탈퇴 요청은 활성 예약으로 인해 WITHDRAWAL_ACTIVE_RESERVATION 예외 발생 확인
                assertThatThrownBy({
                    try {
                        withdrawFuture.get(10, TimeUnit.SECONDS)
                    } catch (e: ExecutionException) {
                        throw e.cause!!
                    }
                })
                    .isInstanceOf(BusinessException::class.java)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WITHDRAWAL_ACTIVE_RESERVATION)

                // 회원 상태는 ACTIVE 유지
                val member = memberRepository.findById(memberId).orElseThrow()
                assertThat(member.status).isEqualTo(MemberStatus.ACTIVE)

                // DB에 예약 1건, 슬롯 2건, 상태 이력 1건 정상 영속화
                val reservations = reservationRepository.findAll()
                assertThat(reservations).hasSize(1)
                assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.HELD)
                assertThat(reservationSlotRepository.count()).isEqualTo(2L)
                assertThat(reservationStatusHistoryRepository.count()).isEqualTo(1L)
            } finally {
                ReflectionTestUtils.setField(memberWithdrawalService, "memberRepository", originalRepo)
            }
        } finally {
            allowHoldCommitLatch.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("탈퇴 선행 시나리오: 탈퇴가 먼저 회원 잠금을 확보하면 HOLD는 대기 후 ACCOUNT_WITHDRAWN으로 거절된다")
    fun withdrawalPreceding_holdBlockedAndFailsWithAccountWithdrawn() {
        val start = LocalDateTime.of(2026, 9, 20, 14, 0)
        val end = LocalDateTime.of(2026, 9, 20, 15, 0)

        val withdrawExecutedLatch = CountDownLatch(1)
        val allowWithdrawCommitLatch = CountDownLatch(1)

        val transactionTemplate = TransactionTemplate(transactionManager)
        val executor = Executors.newFixedThreadPool(2)

        try {
            // Thread 1: 탈퇴 수행 후 트랜잭션 커밋 전 대기 (회원 배타 잠금 유지)
            val withdrawFuture =
                executor.submit<Void?>({
                    transactionTemplate.execute({ _ ->
                        memberWithdrawalService.withdraw(memberId, RAW_PASSWORD)
                        withdrawExecutedLatch.countDown()
                        try {
                            val released = allowWithdrawCommitLatch.await(10, TimeUnit.SECONDS)
                            if (!released) {
                                throw IllegalStateException("allowWithdrawCommitLatch timed out")
                            }
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw RuntimeException(e)
                        }
                        return@execute null
                    })
                })

            // 탈퇴가 회원 잠금을 잡을 때까지 대기
            assertThat(withdrawExecutedLatch.await(5, TimeUnit.SECONDS)).isTrue()

            // HOLD 스레드가 실제 findByIdForUpdate에 진입함을 관측하기 위한 프록시 설정
            val holdEnteredLatch = CountDownLatch(1)
            val originalRepo = ReflectionTestUtils.getField(reservationHoldService, "memberRepository") as MemberRepository
            val proxyRepo =
                Proxy.newProxyInstance(
                    MemberRepository::class.java.getClassLoader(),
                    arrayOf(MemberRepository::class.java),
                    { _, method, args ->
                        if ("findByIdForUpdate".equals(method.getName()) &&
                            (memberId == args!![0])
                        ) {
                            holdEnteredLatch.countDown()
                        }
                        try {
                            return@newProxyInstance method.invoke(originalRepo, *args.orEmpty())
                        } catch (e: InvocationTargetException) {
                            throw e.cause!!
                        }
                    },
                ) as MemberRepository
            ReflectionTestUtils.setField(reservationHoldService, "memberRepository", proxyRepo)

            val holdFuture: Future<ReservationResponse?>
            try {
                // Thread 2: HOLD 요청 시작 -> Member X 락 대기에 진입
                holdFuture = executor.submit<ReservationResponse?>({ reservationHoldService.createHold(memberId, spaceId, start, end) })

                // HOLD 스레드가 실제 findByIdForUpdate 저장소 잠금 호출에 진입했음을 확인
                assertThat(holdEnteredLatch.await(5, TimeUnit.SECONDS)).isTrue()
                // 선행 탈퇴 트랜잭션이 커밋되기 전까지 후행 HOLD 작업이 완료되지 못함을 입증
                assertThat(holdFuture.isDone()).isFalse()

                // 탈퇴 커밋 허용 -> 잠금 해제
                allowWithdrawCommitLatch.countDown()

                // 탈퇴 성공 확인
                withdrawFuture.get(10, TimeUnit.SECONDS)

                // HOLD 요청은 탈퇴로 인해 ACCOUNT_WITHDRAWN 예외 발생 확인
                assertThatThrownBy({
                    try {
                        holdFuture.get(10, TimeUnit.SECONDS)
                    } catch (e: ExecutionException) {
                        throw e.cause!!
                    }
                })
                    .isInstanceOf(BusinessException::class.java)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_WITHDRAWN)

                // 회원 상태는 WITHDRAWN 유지
                val member = memberRepository.findById(memberId).orElseThrow()
                assertThat(member.status).isEqualTo(MemberStatus.WITHDRAWN)

                // 신규 예약·슬롯·이력은 0건
                assertThat(reservationRepository.count()).isZero()
                assertThat(reservationSlotRepository.count()).isZero()
                assertThat(reservationStatusHistoryRepository.count()).isZero()
            } finally {
                ReflectionTestUtils.setField(reservationHoldService, "memberRepository", originalRepo)
            }
        } finally {
            allowWithdrawCommitLatch.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }
}
