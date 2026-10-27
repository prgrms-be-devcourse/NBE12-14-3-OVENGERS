package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.entity.ReservationSlot
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.MySqlTestContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.ArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 동시 예약 요청에 대한 슬롯 유니크 제약 검증(§9, 성공 기준 DoD). 실제 MySQL(Testcontainers)에서
 * 같은 공간·시간에 20개 요청을 동시에 보내 정확히 1건만 성공하는지 확인한다.
 * Docker가 필요하므로 로컬에서 Docker Desktop을 켠 뒤 실행한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainerConfig::class)
class ReservationConcurrencyTest {
    companion object {
        private val THREAD_COUNT: Int = 20
    }

    @Autowired
    private lateinit var reservationHoldService: ReservationHoldService

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

    private var spaceId: Long = 0L
    private lateinit var memberIds: MutableList<Long>
    private lateinit var startTime: LocalDateTime
    private lateinit var endTime: LocalDateTime

    @BeforeEach
    fun setUp() {
        reservationSlotRepository.deleteAll()
        reservationStatusHistoryRepository.deleteAll()
        reservationRepository.deleteAll()

        val space: Space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("동시성 테스트 공간")
                    .location("서울")
                    .capacity(10)
                    .pricePerSlot(5000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(22, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )
        spaceId = space.id!!

        memberIds = ArrayList()
        for (i in 0 until THREAD_COUNT) {
            val member: Member =
                Member(
                    "concurrency-" + i + "-" + System.nanoTime() + "@slotkey.test",
                    "동시성테스트" + i,
                )
            memberIds.add(memberRepository.save(member).id!!)
        }

        startTime =
            LocalDateTime
                .now()
                .plusDays(1)
                .withHour(14)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        endTime = startTime.plusHours(1)
    }

    @AfterEach
    fun tearDown() {
        reservationSlotRepository.deleteAll()
        reservationStatusHistoryRepository.deleteAll()
        reservationRepository.deleteAll()
        memberIds.forEach(memberRepository::deleteById)
        spaceRepository.deleteById(spaceId)
    }

    @Test
    @DisplayName("같은 공간·시간에 20개 요청이 동시에 들어와도 정확히 1건만 성공하고, 나머지는 슬롯 충돌로 실패하며 예약·슬롯 데이터가 남지 않는다")
    fun createHold_concurrentRequests_onlyOneSucceeds() {
        val executor: ExecutorService = Executors.newFixedThreadPool(THREAD_COUNT)
        val readyLatch: CountDownLatch = CountDownLatch(THREAD_COUNT)
        val startLatch: CountDownLatch = CountDownLatch(1)
        val doneLatch: CountDownLatch = CountDownLatch(THREAD_COUNT)
        val successCount: AtomicInteger = AtomicInteger()
        val conflictCount: AtomicInteger = AtomicInteger()

        for (memberId in memberIds) {
            executor.submit({
                readyLatch.countDown()
                try {
                    startLatch.await()
                    reservationHoldService.createHold(memberId, spaceId, startTime, endTime)
                    successCount.incrementAndGet()
                } catch (e: BusinessException) {
                    if (e.errorCode == ErrorCode.RESERVATION_SLOT_CONFLICT) {
                        conflictCount.incrementAndGet()
                    }
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    doneLatch.countDown()
                }
            })
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startLatch.countDown()
        val finished: Boolean = doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertThat(finished).isTrue()
        assertThat(successCount.get()).isEqualTo(1)
        assertThat(conflictCount.get()).isEqualTo(THREAD_COUNT - 1)

        assertThat(reservationRepository.count()).isEqualTo(1L)
        val slots: List<ReservationSlot> = reservationSlotRepository.findAllBySpaceIdAndSlotStartBetween(spaceId, startTime, endTime)
        assertThat(slots).hasSize(2) // 1시간 = 30분 슬롯 2개, 성공한 예약 1건 몫만 남는다
    }
}
