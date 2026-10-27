package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.global.config.ClockConfig
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.idempotency.IdempotencyKeyRepository
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
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
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Import(ReservationHoldMemberLockIntegrationTest.TestClockConfig::class)
class ReservationHoldMemberLockIntegrationTest : IntegrationTestSupport() {
    @TestConfiguration(proxyBeanMethods = false)
    class TestClockConfig {
        companion object {
            @JvmField val FIXED_DATE_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 20, 10, 0, 0)

            @JvmField val FIXED_INSTANT: Instant = FIXED_DATE_TIME.atZone(ClockConfig.DEFAULT_ZONE).toInstant()
        }

        @Bean
        @Primary
        public fun testClock(): Clock = Clock.fixed(FIXED_INSTANT, ClockConfig.DEFAULT_ZONE)
    }

    @Autowired
    private lateinit var reservationHoldService: ReservationHoldService

    @SpyBean
    private lateinit var pricingService: PricingService

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
    private lateinit var clock: Clock

    private var memberId: Long = 0L
    private var spaceId: Long = 0L

    @BeforeEach
    fun setUp() {
        cleanup()

        val member: Member =
            memberRepository.save(
                Member(
                    "holdlock-" + System.nanoTime() + "@slotkey.test",
                    "테스트유저",
                ),
            )
        memberId = member.id!!

        val space: Space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("동시성 테스트 회의실")
                    .location("강남대로 100")
                    .capacity(4)
                    .pricePerSlot(3000L)
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
    @DisplayName("동일 회원의 비중첩 HOLD 두 요청을 회원 잠금 직전에 동기화하여 동시 실행해도 데드락 없이 모두 성공한다")
    fun sameMemberDisjointHolds_synchronizedBeforeMemberLock_bothSucceedWithoutDeadlock() {
        val start1: LocalDateTime = LocalDateTime.of(2026, 9, 20, 14, 0)
        val end1: LocalDateTime = LocalDateTime.of(2026, 9, 20, 15, 0)
        val start2: LocalDateTime = LocalDateTime.of(2026, 9, 20, 15, 0)
        val end2: LocalDateTime = LocalDateTime.of(2026, 9, 20, 16, 0)

        val bothReadyLatch: CountDownLatch = CountDownLatch(2)

        doAnswer({ invocation ->
            bothReadyLatch.countDown()
            val ready: Boolean = bothReadyLatch.await(5, TimeUnit.SECONDS)
            if (!ready) {
                throw IllegalStateException("bothReadyLatch timed out")
            }
            invocation.callRealMethod()
        }).`when`(pricingService).calculateTotalAmount(anyInt(), anyInt())

        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            val f1: Future<ReservationResponse> =
                executor.submit(
                    Callable { reservationHoldService.createHold(memberId, spaceId, start1, end1) },
                )
            val f2: Future<ReservationResponse> =
                executor.submit(
                    Callable { reservationHoldService.createHold(memberId, spaceId, start2, end2) },
                )

            val r1: ReservationResponse = f1.get(10, TimeUnit.SECONDS)
            val r2: ReservationResponse = f2.get(10, TimeUnit.SECONDS)

            assertThat(r1).isNotNull()
            assertThat(r2).isNotNull()
            assertThat(r1.reservationId).isNotEqualTo(r2.reservationId)

            val reservations: List<Reservation> = reservationRepository.findAll()
            assertThat(reservations).hasSize(2)
            assertThat(reservations).allMatch({ r -> r.getStatus() == ReservationStatus.HELD })
            assertThat(reservations).allMatch({ r -> r.getMemberId()!!.equals(memberId) })

            val slotCount: Long = reservationSlotRepository.count()
            assertThat(slotCount).isEqualTo(4L)

            val historyCount: Long = reservationStatusHistoryRepository.count()
            assertThat(historyCount).isEqualTo(2L)
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("동일 회원이 동일 슬롯에 대해 동시 HOLD를 요청하면 1건만 성공하고 1건은 RESERVATION_SLOT_CONFLICT로 실패한다")
    fun sameMemberSameSlotConcurrentHolds_oneSucceedsOneFailsWithSlotConflict() {
        val start: LocalDateTime = LocalDateTime.of(2026, 9, 20, 14, 0)
        val end: LocalDateTime = LocalDateTime.of(2026, 9, 20, 15, 0)

        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            val task: Callable<ReservationResponse> = Callable { reservationHoldService.createHold(memberId, spaceId, start, end) }

            val futures: List<Future<ReservationResponse>> = executor.invokeAll(listOf(task, task), 10, TimeUnit.SECONDS)
            for (future in futures) {
                assertThat(future.isDone).`as`("각 태스크는 10초 이내에 완료되어야 한다").isTrue()
            }

            val successCount: AtomicInteger = AtomicInteger()
            val conflictCount: AtomicInteger = AtomicInteger()
            val exceptions: MutableList<Throwable?> = ArrayList()

            for (future in futures) {
                try {
                    future.get(10, TimeUnit.SECONDS)
                    successCount.incrementAndGet()
                } catch (e: ExecutionException) {
                    val cause: Throwable? = e.cause
                    if (cause is BusinessException &&
                        cause.errorCode == ErrorCode.RESERVATION_SLOT_CONFLICT
                    ) {
                        conflictCount.incrementAndGet()
                    } else {
                        exceptions.add(cause)
                    }
                }
            }

            assertThat(exceptions).isEmpty()
            assertThat(successCount.get()).isEqualTo(1)
            assertThat(conflictCount.get()).isEqualTo(1)

            val reservations: List<Reservation> = reservationRepository.findAll()
            assertThat(reservations).hasSize(1)
            assertThat(reservations.get(0).getStatus()).isEqualTo(ReservationStatus.HELD)

            val slotCount: Long = reservationSlotRepository.count()
            assertThat(slotCount).isEqualTo(2L)

            val historyCount: Long = reservationStatusHistoryRepository.count()
            assertThat(historyCount).isEqualTo(1L)
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("탈퇴한 회원이면 ACCOUNT_WITHDRAWN 예외가 발생하고 예약·슬롯·이력 DB 데이터가 남지 않는다")
    fun withdrawnMemberHold_throwsAccountWithdrawnAndLeavesNoData() {
        val now: LocalDateTime = LocalDateTime.now(clock)
        val member: Member = memberRepository.findById(memberId).orElseThrow()
        member.withdraw(now)
        memberRepository.saveAndFlush(member)

        val start: LocalDateTime = LocalDateTime.of(2026, 9, 20, 14, 0)
        val end: LocalDateTime = LocalDateTime.of(2026, 9, 20, 15, 0)

        assertThatThrownBy({ reservationHoldService.createHold(memberId, spaceId, start, end) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_WITHDRAWN)

        assertThat(reservationRepository.count()).isZero()
        assertThat(reservationSlotRepository.count()).isZero()
        assertThat(reservationStatusHistoryRepository.count()).isZero()
    }

    @Test
    @DisplayName("존재하지 않는 회원 ID이면 AUTHENTICATION_REQUIRED 예외가 발생하고 예약·슬롯·이력 DB 데이터가 남지 않는다")
    fun nonExistentMemberHold_throwsAuthenticationRequiredAndLeavesNoData() {
        val nonExistentMemberId: Long = 999_999L
        val start: LocalDateTime = LocalDateTime.of(2026, 9, 20, 14, 0)
        val end: LocalDateTime = LocalDateTime.of(2026, 9, 20, 15, 0)

        assertThatThrownBy({ reservationHoldService.createHold(nonExistentMemberId, spaceId, start, end) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTHENTICATION_REQUIRED)

        assertThat(reservationRepository.count()).isZero()
        assertThat(reservationSlotRepository.count()).isZero()
        assertThat(reservationStatusHistoryRepository.count()).isZero()
    }
}
