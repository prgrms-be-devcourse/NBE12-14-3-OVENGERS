package com.ovengers.slotkey.stats.service

import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.stats.entity.RankingPeriodType
import com.ovengers.slotkey.stats.repository.SpaceRankingSnapshotRepository
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.support.SettableClockConfig
import com.ovengers.slotkey.support.SettableClockConfig.SettableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

@Import(SettableClockConfig::class)
class SpaceRankingScheduleBackfillConcurrencyIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var scheduler: SpaceRankingScheduler

    @Autowired
    private lateinit var backfillService: SpaceRankingBackfillService

    @SpyBean
    private lateinit var confirmService: SpaceRankingConfirmService

    @Autowired
    private lateinit var snapshotRepository: SpaceRankingSnapshotRepository

    @Autowired
    private lateinit var settableClock: SettableClock

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private var memberId: Long = 0
    private var spaceId: Long = 0

    private val targetDate = LocalDate.of(2026, 10, 15) // 목요일 (전일)
    private val clockNow = ZonedDateTime.of(2026, 10, 16, 0, 1, 0, 0, SettableClockConfig.KST) // 금요일 00:01

    private fun anyPeriodType(): RankingPeriodType {
        Mockito.any(RankingPeriodType::class.java)
        return RankingPeriodType.DAILY
    }

    private fun anyLocalDate(): LocalDate {
        Mockito.any(LocalDate::class.java)
        return LocalDate.MIN
    }

    @BeforeEach
    fun setUp() {
        settableClock.set(clockNow)
        cleanSnapshots()

        memberId = createMember(1_000_000)
        spaceId = createSpace()
        jdbcTemplate.update("UPDATE spaces SET location = '판교' WHERE id = ?", spaceId)

        // 대상 일자(2026-10-15)에 결제 및 예약 데이터 생성
        val start = LocalDateTime.of(2026, 10, 15, 14, 0)
        val end = start.plusMinutes(60)
        val resId = insertReservation(ReservationStatus.CONFIRMED, start, end)
        charge(resId, 10000, start.minusHours(1))
    }

    @AfterEach
    fun tearDown() {
        cleanSnapshots()
    }

    private fun cleanSnapshots() {
        jdbcTemplate.update("DELETE FROM space_ranking_snapshot")
    }

    data class CallerArrival(
        val caller: String,
        val periodType: RankingPeriodType,
        val dateInPeriod: LocalDate,
        val arrivalOrder: Int,
        val timestamp: Long,
    )

    data class CallerCompletion(
        val caller: String,
        val periodType: RankingPeriodType,
        val dateInPeriod: LocalDate,
        val success: Boolean,
        val result: Any?,
        val exceptionType: String?,
        val sqlState: String?,
        val vendorCode: Int?,
        val durationMs: Long,
    )

    @Test
    @DisplayName("스케줄러 runDaily와 수동 backfillMissing이 동일 기간 확정에 동시 진입할 때 CyclicBarrier 동기화 하에서 트랜잭션 경합을 안전하게 직렬화하고 스냅샷을 1건 보존한다")
    fun concurrentRunDailyAndBackfillMissing_controlledByBarrier_observesContentionAndExposesDeadlock() {
        val targetPeriod = RankingPeriodType.DAILY
        val barrier = CyclicBarrier(2)
        val arrivalSequence = AtomicInteger(0)

        val arrivalRecords = CopyOnWriteArrayList<CallerArrival>()
        val completionRecords = CopyOnWriteArrayList<CallerCompletion>()
        val capturedExceptions = CopyOnWriteArrayList<Throwable>()

        doAnswer { invocation ->
            val periodType = invocation.getArgument<RankingPeriodType>(0)
            val dateInPeriod = invocation.getArgument<LocalDate>(1)

            val callerName =
                if (Thread.currentThread().name.contains("scheduler", ignoreCase = true)) {
                    "scheduler"
                } else if (Thread.currentThread().name.contains("backfill", ignoreCase = true)) {
                    "backfill"
                } else {
                    val stack = Thread.currentThread().stackTrace
                    if (stack.any { it.className.contains("SpaceRankingScheduler") }) {
                        "scheduler"
                    } else if (stack.any { it.className.contains("SpaceRankingBackfillService") }) {
                        "backfill"
                    } else {
                        "unknown-${Thread.currentThread().name}"
                    }
                }

            val isTarget = (periodType == targetPeriod && periodType.startOf(dateInPeriod) == targetDate)

            if (isTarget) {
                val order = arrivalSequence.incrementAndGet()
                arrivalRecords.add(CallerArrival(callerName, periodType, dateInPeriod, order, System.currentTimeMillis()))

                // barrier await 성공을 assertion으로 강제 (예외 무시 금지)
                val awaitIndex = barrier.await(10, TimeUnit.SECONDS)
                assertThat(awaitIndex).isGreaterThanOrEqualTo(0)
            }

            val startTime = System.currentTimeMillis()
            try {
                val res = invocation.callRealMethod()
                val duration = System.currentTimeMillis() - startTime
                completionRecords.add(
                    CallerCompletion(
                        caller = callerName,
                        periodType = periodType,
                        dateInPeriod = dateInPeriod,
                        success = true,
                        result = res,
                        exceptionType = null,
                        sqlState = null,
                        vendorCode = null,
                        durationMs = duration,
                    ),
                )
                res
            } catch (t: Throwable) {
                val duration = System.currentTimeMillis() - startTime
                var sqlState: String? = null
                var vendorCode: Int? = null
                var cur: Throwable? = t
                while (cur != null) {
                    if (cur is SQLException) {
                        sqlState = cur.sqlState
                        vendorCode = cur.errorCode
                        break
                    }
                    cur = cur.cause
                }
                log.warn(
                    "confirmService.confirm 경합 예외 관측: caller={}, error={}, sqlState={}, vendorCode={}",
                    callerName,
                    t::class.java.simpleName,
                    sqlState,
                    vendorCode,
                )
                completionRecords.add(
                    CallerCompletion(
                        caller = callerName,
                        periodType = periodType,
                        dateInPeriod = dateInPeriod,
                        success = false,
                        result = null,
                        exceptionType = t::class.java.name,
                        sqlState = sqlState,
                        vendorCode = vendorCode,
                        durationMs = duration,
                    ),
                )
                capturedExceptions.add(t)
                throw t
            }
        }.`when`(confirmService).confirm(anyPeriodType(), anyLocalDate())

        val executor =
            Executors.newFixedThreadPool(2) { runnable ->
                val threadName =
                    if (arrivalSequence.get() == 0 &&
                        !Thread.currentThread().name.contains("pool")
                    ) {
                        "scheduler-worker"
                    } else {
                        "backfill-worker"
                    }
                Thread(runnable, threadName)
            }

        try {
            // Caller 1: 스케줄러 runDaily() 실행
            val future1 =
                executor.submit {
                    Thread.currentThread().name = "scheduler-thread"
                    scheduler.runDaily()
                }

            // Caller 2: 백필 서비스 backfillMissing() 실행
            val future2 =
                executor.submit {
                    Thread.currentThread().name = "backfill-thread"
                    backfillService.backfillMissing()
                }

            // 두 작업의 Future 모두 유한 시간(15초) 내에 안전하게 회수 (첫 future 실패 시에도 나머지 회수 보장)
            var f1Exception: Throwable? = null
            var f2Exception: Throwable? = null
            try {
                future1.get(15, TimeUnit.SECONDS)
            } catch (e: Throwable) {
                f1Exception = e
            }
            try {
                future2.get(15, TimeUnit.SECONDS)
            } catch (e: Throwable) {
                f2Exception = e
            }

            if (f1Exception != null) throw f1Exception
            if (f2Exception != null) throw f2Exception
        } finally {
            executor.shutdown()
            val terminated = executor.awaitTermination(5, TimeUnit.SECONDS)
            assertThat(terminated).isTrue()
        }

        // 1. 참가자 및 Barrier 검증:
        // scheduler와 backfill 서로 다른 호출자가 대상 기간 확정에 모두 진입했는지 확인
        val targetArrivals = arrivalRecords.filter { it.periodType == targetPeriod && it.periodType.startOf(it.dateInPeriod) == targetDate }
        val arrivedCallers = targetArrivals.map { it.caller }
        assertThat(arrivedCallers).contains("scheduler", "backfill")
        assertThat(barrier.isBroken).isFalse()

        // 2. 대상 기간(2026-10-15 DAILY)에 대한 양측 호출의 완료 및 반환 검증
        val targetCompletions =
            completionRecords.filter {
                it.periodType == targetPeriod && it.periodType.startOf(it.dateInPeriod) == targetDate
            }
        assertThat(targetCompletions).hasSize(2)
        val successfulCallers = targetCompletions.filter { it.success }.map { it.caller }
        assertThat(successfulCallers).containsExactlyInAnyOrder("scheduler", "backfill")
        assertThat(targetCompletions.map { it.result as Int }).containsOnly(1)

        // 3. 최종 DB 상태 관측: hasSize(1) 및 정확한 필드 검증
        val snapshots = snapshotRepository.findByPeriodTypeAndPeriodStart(targetPeriod, targetDate)
        assertThat(snapshots).hasSize(1)
        val snapshot = snapshots.first()
        assertThat(snapshot.spaceId).isEqualTo(spaceId)
        assertThat(snapshot.location).isEqualTo("판교")
        assertThat(snapshot.netRevenue).isEqualTo(10000L)
        assertThat(snapshot.reservationCount).isEqualTo(1)
        assertThat(snapshot.slotCount).isEqualTo(2)

        // 4. 트랜잭션 경합 및 예외 검증 (예외 0건)
        assertThat(capturedExceptions)
            .withFailMessage(
                "동일 기간 동시 확정 시 트랜잭션 경합으로 confirm 실패(Deadlock 1213/40001) 발생. " +
                    "관측된 완료 기록: %s, 예외 목록: %s",
                completionRecords,
                capturedExceptions.map { "${it.javaClass.simpleName}: ${it.message}" },
            ).isEmpty()
    }

    @Test
    @DisplayName("(a) 상위 부모 트랜잭션 커밋 전 직렬화 유지: Thread B는 Thread A의 부모 트랜잭션 commit 완료 전까지 임계구역에 진입하지 못한다")
    fun parentTransaction_holdsLockUntilCommit_serializesConcurrentConfirm() {
        val targetPeriod = RankingPeriodType.DAILY
        val events = CopyOnWriteArrayList<String>()
        val latchAConfirmReturned = CountDownLatch(1)
        val latchBInvokedConfirm = CountDownLatch(1)
        val latchReleaseACommit = CountDownLatch(1)
        val threadBRef = AtomicReference<Thread>()

        doAnswer { invocation ->
            val threadName = Thread.currentThread().name
            if (threadName == "thread-b") {
                threadBRef.set(Thread.currentThread())
                events.add("B_BEFORE_CONFIRM")
                latchBInvokedConfirm.countDown()
                val result = invocation.callRealMethod()
                events.add("B_CONFIRM_RETURNED")
                result
            } else {
                invocation.callRealMethod()
            }
        }.`when`(confirmService).confirm(anyPeriodType(), anyLocalDate())

        val executor = Executors.newFixedThreadPool(2)

        try {
            // Thread A: 부모 트랜잭션 내에서 confirm 호출 후 afterCompletion 콜백 등록 및 커밋 직전 대기
            val futureA =
                executor.submit(
                    java.util.concurrent.Callable {
                        Thread.currentThread().name = "thread-a"
                        transactionTemplate.execute {
                            TransactionSynchronizationManager.registerSynchronization(
                                object : TransactionSynchronization {
                                    override fun afterCompletion(status: Int) {
                                        events.add("A_TX_AFTER_COMPLETION:$status")
                                    }
                                },
                            )
                            confirmService.confirm(targetPeriod, targetDate)
                            events.add("A_CONFIRM_RETURNED")
                            latchAConfirmReturned.countDown()

                            // 부모 트랜잭션 커밋 허용 신호 대기
                            val ok = latchReleaseACommit.await(10, TimeUnit.SECONDS)
                            if (!ok) throw IllegalStateException("latchReleaseACommit timed out")
                            true
                        }
                    },
                )

            // Thread A의 confirm() 반환 확인
            val aReturned = latchAConfirmReturned.await(10, TimeUnit.SECONDS)
            assertThat(aReturned).isTrue()

            // Thread B 시작: Thread A가 아직 커밋 전이므로 lock 획득 시도 시 WAITING 상태로 블록되어야 함
            val futureB =
                executor.submit(
                    java.util.concurrent.Callable {
                        Thread.currentThread().name = "thread-b"
                        confirmService.confirm(targetPeriod, targetDate)
                    },
                )

            // Thread B가 doAnswer의 B_BEFORE_CONFIRM에 도달했는지 확인
            val bInvoked = latchBInvokedConfirm.await(10, TimeUnit.SECONDS)
            assertThat(bInvoked).isTrue()

            // Thread B가 confirmLock.lock()에서 WAITING 상태로 진입할 때까지 유한 폴링 (최대 5초)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val bThread = threadBRef.get()
                if (bThread != null && bThread.state == Thread.State.WAITING) {
                    break
                }
                LockSupport.parkNanos(1_000_000) // 1ms
            }

            val bThread = threadBRef.get()
            assertThat(bThread).isNotNull
            assertThat(bThread!!.state).isEqualTo(Thread.State.WAITING)

            // Thread A의 커밋 직전 시점 단언: Thread B는 BEFORE_CONFIRM까지만 기록되고 CONFIRM_RETURNED는 발생하지 않음
            assertThat(events).containsExactly("A_CONFIRM_RETURNED", "B_BEFORE_CONFIRM")

            // 이제 Thread A의 커밋 허용
            latchReleaseACommit.countDown()

            futureA.get(10, TimeUnit.SECONDS)
            futureB.get(10, TimeUnit.SECONDS)

            // 최종 이벤트 순서가 결정론적으로 일치함을 단언
            assertThat(events).containsExactly(
                "A_CONFIRM_RETURNED",
                "B_BEFORE_CONFIRM",
                "A_TX_AFTER_COMPLETION:0",
                "B_CONFIRM_RETURNED",
            )
        } finally {
            latchReleaseACommit.countDown()
            executor.shutdownNow()
            val terminated = executor.awaitTermination(5, TimeUnit.SECONDS)
            assertThat(terminated).isTrue()
        }

        val snapshots = snapshotRepository.findByPeriodTypeAndPeriodStart(targetPeriod, targetDate)
        assertThat(snapshots).hasSize(1)
    }

    @Test
    @DisplayName("(b) 동일 스레드 재진입: 부모 트랜잭션 내에서 중복 confirm 호출 시 afterCompletion에서 락이 남김없이 해제되어 다른 스레드가 정상 진입한다")
    fun sameThreadReentrantCalls_insideParentTx_releasesLockCleanly() {
        val targetPeriod = RankingPeriodType.DAILY

        // 부모 트랜잭션 내에서 동일 스레드가 2회 연속 confirm 호출 (재진입)
        transactionTemplate.execute {
            val r1 = confirmService.confirm(targetPeriod, targetDate)
            assertThat(r1).isEqualTo(1)
            val r2 = confirmService.confirm(targetPeriod, targetDate)
            assertThat(r2).isEqualTo(1)
        }

        // 트랜잭션 종료 후 다른 스레드가 즉시 락을 획득하고 confirm을 성공적으로 수행할 수 있어야 함
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future =
                executor.submit<Int> {
                    confirmService.confirm(targetPeriod, targetDate)
                }
            val result = future.get(5, TimeUnit.SECONDS)
            assertThat(result).isEqualTo(1)
        } finally {
            executor.shutdown()
            val terminated = executor.awaitTermination(5, TimeUnit.SECONDS)
            assertThat(terminated).isTrue()
        }

        val snapshots = snapshotRepository.findByPeriodTypeAndPeriodStart(targetPeriod, targetDate)
        assertThat(snapshots).hasSize(1)
    }

    @Test
    @DisplayName("(c) 롤백 후 락 해제 및 미확정 기간(IllegalArgumentException) 회귀 검증: 락 누수가 발생하지 않는다")
    fun rollbackAndIllegalArgumentException_releasesLockWithoutLeak() {
        val targetPeriod = RankingPeriodType.DAILY

        // 1. 미확정 기간 (미래 일자) 호출 시 IllegalArgumentException 던지고 락을 점유하지 않아야 함
        val futureDate = LocalDate.of(2026, 10, 20)
        assertThatThrownBy {
            confirmService.confirm(targetPeriod, futureDate)
        }.isInstanceOf(IllegalArgumentException::class.java)

        // 2. 부모 트랜잭션 내부에서 confirm 호출 후 고의 예외로 롤백 발생
        assertThatThrownBy {
            transactionTemplate.execute<Void> {
                confirmService.confirm(targetPeriod, targetDate)
                throw RuntimeException("강제 롤백 테스트")
            }
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessage("강제 롤백 테스트")

        // 롤백 이후 다른 스레드(또는 후속 호출)가 락 대기 없이 정상적으로 confirm 수행 가능해야 함
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future =
                executor.submit<Int> {
                    confirmService.confirm(targetPeriod, targetDate)
                }
            val result = future.get(5, TimeUnit.SECONDS)
            assertThat(result).isEqualTo(1)
        } finally {
            executor.shutdown()
            val terminated = executor.awaitTermination(5, TimeUnit.SECONDS)
            assertThat(terminated).isTrue()
        }

        val snapshots = snapshotRepository.findByPeriodTypeAndPeriodStart(targetPeriod, targetDate)
        assertThat(snapshots).hasSize(1)
    }

    // ---------- helpers ----------

    private fun insertReservation(
        status: ReservationStatus,
        start: LocalDateTime,
        end: LocalDateTime,
    ): Long {
        val startTs = Timestamp.valueOf(start)
        jdbcTemplate.update(
            """
            INSERT INTO reservation (member_id, space_id, start_time, end_time, status,
                price_per_slot_snapshot, total_amount, hold_expires_at, checked_in_at,
                checked_out_at, cancelled_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            memberId,
            spaceId,
            startTs,
            Timestamp.valueOf(end),
            status.name,
            5000,
            10000,
            null,
            startTs,
            Timestamp.valueOf(end),
            null,
            Timestamp.valueOf(start.minusDays(1)),
        )
        return jdbcTemplate.queryForObject(
            "SELECT MAX(id) FROM reservation WHERE space_id = ?",
            Long::class.java,
            spaceId,
        )
    }

    private fun charge(
        reservationId: Long,
        amount: Int,
        at: LocalDateTime,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO credit_transaction (member_id, amount, type, reservation_id, balance_after, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            memberId,
            -amount,
            "RESERVATION_CHARGE",
            reservationId,
            0,
            Timestamp.valueOf(at),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(SpaceRankingScheduleBackfillConcurrencyIntegrationTest::class.java)
    }
}
