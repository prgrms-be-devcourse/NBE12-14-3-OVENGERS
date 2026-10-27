package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.credit.service.CreditServiceImpl
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.support.eqNonNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.test.util.ReflectionTestUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReservationExtendHoldConcurrencyIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var holdService: ReservationHoldService

    @Autowired
    private lateinit var extendService: ReservationExtendService

    @SpyBean
    private lateinit var creditService: CreditServiceImpl

    @SpyBean
    private lateinit var expirationService: ReservationHoldExpirationService

    @Test
    @DisplayName("동일 회원의 연장(15~16시)과 기존 예약 구간 HOLD(14~15시) 동시 경합 시 데드락 없이 연장 성공 및 HOLD 슬롯충돌 거절")
    fun existingSlotOverlapHold_mustNotDeadlockWithValidExtension() {
        val memberId = createMember(100_000)
        val spaceId = createSpace()
        val start = tomorrowAt(14, 0)
        val end = tomorrowAt(15, 0)
        val reservationId = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, start, end)

        val extendReachedCharge = CountDownLatch(1)
        val resume = CountDownLatch(1)

        // 연장이 Member X 잠금과 추가 슬롯 확보를 마친 후 creditService.charge 진입 시점에 대기
        doAnswer({ invocation ->
            extendReachedCharge.countDown()
            assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue()
            return@doAnswer invocation.callRealMethod()
        }).`when`(creditService).charge(eqNonNull(memberId), eqNonNull(reservationId), anyInt())

        // HOLD가 memberRepository.findByIdForUpdate 호출에 진입함을 관측하기 위한 프록시 설정
        val holdEnteredMemberLock = CountDownLatch(1)
        val originalRepo =
            ReflectionTestUtils.getField(
                holdService,
                "memberRepository",
            ) as MemberRepository
        val proxyRepo =
            Proxy.newProxyInstance(
                MemberRepository::class.java.getClassLoader(),
                arrayOf(MemberRepository::class.java),
                { proxy, method, args ->
                    if ("findByIdForUpdate".equals(method.getName()) &&
                        (memberId == args!![0])
                    ) {
                        holdEnteredMemberLock.countDown()
                    }
                    try {
                        return@newProxyInstance method.invoke(originalRepo, *args.orEmpty())
                    } catch (e: InvocationTargetException) {
                        throw e.cause!!
                    }
                },
            ) as MemberRepository
        ReflectionTestUtils.setField(holdService, "memberRepository", proxyRepo)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val extendFuture =
                executor.submit<Throwable?>(
                    { capture({ extendService.extend(memberId, reservationId, end, end.plusHours(1)) }) },
                )

            assertThat(extendReachedCharge.await(10, TimeUnit.SECONDS)).`as`("연장이 크레딧 차감 단계에 도달해야 한다").isTrue()

            // 기존 예약 구간(14:00~15:00)으로 동일 회원이 신규 HOLD 요청
            val holdFuture = executor.submit<Throwable?>({ capture({ holdService.createHold(memberId, spaceId, start, end) }) })

            // HOLD가 실제로 findByIdForUpdate 저장소 잠금 호출에 진입했음을 확인
            assertThat(holdEnteredMemberLock.await(5, TimeUnit.SECONDS))
                .`as`("HOLD가 회원 잠금 획득 호출에 진입해야 한다")
                .isTrue()
            // 연장 트랜잭션이 아직 커밋되지 않았으므로 HOLD 작업이 완료되지 못하고 대기 중임을 실증
            assertThat(holdFuture.isDone()).`as`("연장이 커밋되기 전에는 HOLD가 완료되지 않아야 한다").isFalse()

            // 연장 재개 및 커밋 허용
            resume.countDown()

            val extendFailure = extendFuture.get(15, TimeUnit.SECONDS)
            val holdFailure = holdFuture.get(15, TimeUnit.SECONDS)

            // 1. 연장은 데드락 없이 반드시 성공해야 함
            assertThat(extendFailure).`as`("유효한 연장은 성공해야 한다").isNull()

            // 2. HOLD는 데드락(CannotAcquireLockException)이 아닌 도메인 슬롯 충돌(RESERVATION_SLOT_CONFLICT)이어야 함
            assertThat(holdFailure)
                .`as`("기존 슬롯 중복 HOLD는 RESERVATION_SLOT_CONFLICT로 거절되어야 한다")
                .isInstanceOf(BusinessException::class.java)
            assertThat((holdFailure as BusinessException).errorCode)
                .isEqualTo(ErrorCode.RESERVATION_SLOT_CONFLICT)

            // 3. DB 정합성: 연장된 예약은 16:00까지 총 4개 슬롯, 크레딧 10,000원 차감, 원장 1건 영속화
            assertThat(statusOf(reservationId)).isEqualTo(ReservationStatus.CONFIRMED.name)
            assertThat(reservationRepository.findById(reservationId).orElseThrow().getEndTime())
                .isEqualTo(end.plusHours(1))
            assertThat(countSlots(reservationId)).isEqualTo(4)
            assertThat(balanceOf(memberId)).isEqualTo(90_000)
            assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isEqualTo(1)
        } finally {
            ReflectionTestUtils.setField(holdService, "memberRepository", originalRepo)
            resume.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("동일 회원의 연장(15~16시)과 미커밋 추가 슬롯 구간 HOLD(15~16시) 동시 경합 시 데드락 없이 연장 성공 및 HOLD 슬롯충돌 거절")
    fun additionalSlotOverlapHold_mustNotDeadlockWithValidExtension() {
        val memberId = createMember(100_000)
        val spaceId = createSpace()
        val start = tomorrowAt(14, 0)
        val end = tomorrowAt(15, 0)
        val extendEnd = tomorrowAt(16, 0)
        val reservationId = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, start, end)

        val extendReachedCharge = CountDownLatch(1)
        val resume = CountDownLatch(1)

        // 연장이 Member X 잠금과 추가 슬롯(15:00, 15:30) INSERT를 마친 후 creditService.charge 진입 시점에 대기
        doAnswer({ invocation ->
            extendReachedCharge.countDown()
            assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue()
            return@doAnswer invocation.callRealMethod()
        }).`when`(creditService).charge(eqNonNull(memberId), eqNonNull(reservationId), anyInt())

        // HOLD가 memberRepository.findByIdForUpdate 호출에 진입함을 관측하기 위한 프록시 설정
        val holdEnteredMemberLock = CountDownLatch(1)
        val originalRepo =
            ReflectionTestUtils.getField(
                holdService,
                "memberRepository",
            ) as MemberRepository
        val proxyRepo =
            Proxy.newProxyInstance(
                MemberRepository::class.java.getClassLoader(),
                arrayOf(MemberRepository::class.java),
                { proxy, method, args ->
                    if ("findByIdForUpdate".equals(method.getName()) &&
                        (memberId == args!![0])
                    ) {
                        holdEnteredMemberLock.countDown()
                    }
                    try {
                        return@newProxyInstance method.invoke(originalRepo, *args.orEmpty())
                    } catch (e: InvocationTargetException) {
                        throw e.cause!!
                    }
                },
            ) as MemberRepository
        ReflectionTestUtils.setField(holdService, "memberRepository", proxyRepo)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val extendFuture = executor.submit<Throwable?>({ capture({ extendService.extend(memberId, reservationId, end, extendEnd) }) })

            assertThat(extendReachedCharge.await(10, TimeUnit.SECONDS)).`as`("연장이 크레딧 차감 단계에 도달해야 한다").isTrue()

            // 연장의 미커밋 추가 구간(15:00~16:00)과 완전히 겹치는 구간으로 동일 회원이 신규 HOLD 요청
            // 연장이 이미 Member X 잠금을 쥐고 있으므로 HOLD는 Member 잠금에서 row-lock 대기하고,
            // 연장 커밋 후 깨어나 슬롯 INSERT 시점에 UNIQUE 충돌로 정상 거절된다 (데드락 없음).
            val holdFuture = executor.submit<Throwable?>({ capture({ holdService.createHold(memberId, spaceId, end, extendEnd) }) })

            // HOLD가 실제로 findByIdForUpdate 저장소 잠금 호출에 진입했음을 확인
            assertThat(holdEnteredMemberLock.await(5, TimeUnit.SECONDS))
                .`as`("HOLD가 회원 잠금 획득 호출에 진입해야 한다")
                .isTrue()
            // 연장 트랜잭션이 아직 커밋되지 않았으므로 HOLD 작업이 완료되지 못하고 대기 중임을 실증
            assertThat(holdFuture.isDone()).`as`("연장이 커밋되기 전에는 HOLD가 완료되지 않아야 한다").isFalse()

            // 연장 재개 및 커밋 허용
            resume.countDown()

            val extendFailure = extendFuture.get(15, TimeUnit.SECONDS)
            val holdFailure = holdFuture.get(15, TimeUnit.SECONDS)

            // 1. 연장은 데드락 없이 반드시 성공해야 함
            assertThat(extendFailure).`as`("유효한 연장은 성공해야 한다").isNull()

            // 2. HOLD는 데드락(CannotAcquireLockException, 1213)이 아닌 도메인 슬롯 충돌(RESERVATION_SLOT_CONFLICT)이어야 함
            assertThat(holdFailure)
                .`as`("추가 슬롯 중복 HOLD는 RESERVATION_SLOT_CONFLICT로 거절되어야 한다")
                .isInstanceOf(BusinessException::class.java)
            assertThat((holdFailure as BusinessException).errorCode)
                .isEqualTo(ErrorCode.RESERVATION_SLOT_CONFLICT)

            // 3. DB 정합성: 연장된 예약은 16:00까지 총 4개 슬롯, 크레딧 10,000원 차감, 원장 1건 영속화
            assertThat(statusOf(reservationId)).isEqualTo(ReservationStatus.CONFIRMED.name)
            assertThat(reservationRepository.findById(reservationId).orElseThrow().getEndTime())
                .isEqualTo(extendEnd)
            assertThat(countSlots(reservationId)).isEqualTo(4)
            assertThat(balanceOf(memberId)).isEqualTo(90_000)
            assertThat(countLedger(reservationId, "RESERVATION_CHARGE")).isEqualTo(1)
        } finally {
            ReflectionTestUtils.setField(holdService, "memberRepository", originalRepo)
            resume.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("만료된 HELD가 걸려있는 슬롯에 신규 HOLD 요청 시 기존 HELD가 EXPIRED로 정리되고 신규 HOLD 성공")
    fun hold_cleansUpExpiredHold_andSucceeds() {
        val memberA = createMember(100_000)
        val memberB = createMember(100_000)
        val spaceId = createSpace()
        val start = tomorrowAt(10, 0)
        val end = tomorrowAt(11, 0)

        // memberA의 HELD 생성 (만료 시각을 10분 전으로 설정하여 만료된 상태)
        val expiredHoldId = createReservation(memberA, spaceId, ReservationStatus.HELD, start, end)
        jdbcTemplate.update(
            "UPDATE reservation SET hold_expires_at = ? WHERE id = ?",
            now().minusMinutes(10),
            expiredHoldId,
        )

        // memberB의 동일 시간대 HOLD 요청 -> 만료 정리 후 정상 성공해야 함
        val response = holdService.createHold(memberB, spaceId, start, end)

        assertThat(response).isNotNull()
        assertThat(response.status).isEqualTo("HELD")

        // 기존 memberA의 예약은 EXPIRED로 전이되고 이력 생성
        assertThat(statusOf(expiredHoldId)).isEqualTo(ReservationStatus.EXPIRED.name)
        assertThat(countHistory(expiredHoldId, "EXPIRED")).isEqualTo(1)

        // memberA의 기존 슬롯은 삭제되고 memberB의 새 슬롯 2건만 남음
        assertThat(countSlots(expiredHoldId)).isEqualTo(0)
        assertThat(countSlots(response.reservationId)).isEqualTo(2)
    }

    @Test
    @DisplayName("이미 EXPIRED 상태인 예약의 슬롯 잔재가 남아있는 경우 신규 HOLD 요청 시 잔재 삭제 후 정상 성공")
    fun hold_cleansUpOrphanExpiredSlot_andSucceeds() {
        val memberA = createMember(100_000)
        val memberB = createMember(100_000)
        val spaceId = createSpace()
        val start = tomorrowAt(10, 0)
        val end = tomorrowAt(11, 0)

        // memberA의 EXPIRED 예약 생성 (슬롯은 잔재로 남아있음)
        val expiredId = createReservation(memberA, spaceId, ReservationStatus.EXPIRED, start, end)

        // memberB의 동일 시간대 HOLD 요청 -> 잔재 슬롯 정리 후 정상 성공해야 함
        val response = holdService.createHold(memberB, spaceId, start, end)

        assertThat(response).isNotNull()
        assertThat(response.status).isEqualTo("HELD")
        assertThat(countSlots(expiredId)).isEqualTo(0)
        assertThat(countSlots(response.reservationId)).isEqualTo(2)
    }

    @Test
    @DisplayName("연장하려는 추가 구간에 다른 회원의 만료된 HELD가 걸려있을 때 연장 시 만료 HELD가 정리되고 연장 성공")
    fun extend_cleansUpExpiredHoldInExtensionSlot_andSucceeds() {
        val memberA = createMember(100_000)
        val memberB = createMember(100_000)
        val spaceId = createSpace()
        val start = tomorrowAt(10, 0)
        val mid = tomorrowAt(11, 0)
        val end = tomorrowAt(12, 0)

        // memberA의 CONFIRMED 예약: 10:00 ~ 11:00
        val reservationA = createReservation(memberA, spaceId, ReservationStatus.CONFIRMED, start, mid)

        // memberB의 HELD 예약: 11:00 ~ 12:00 (만료된 상태)
        val expiredHoldB = createReservation(memberB, spaceId, ReservationStatus.HELD, mid, end)
        jdbcTemplate.update(
            "UPDATE reservation SET hold_expires_at = ? WHERE id = ?",
            now().minusMinutes(10),
            expiredHoldB,
        )

        // memberA가 11:00 ~ 12:00 구간으로 연장 요청 -> memberB 만료 정리 후 연장 성공
        val response = extendService.extend(memberA, reservationA, mid, end)

        assertThat(response).isNotNull()
        assertThat(response.endTime).isEqualTo(end)
        assertThat(statusOf(reservationA)).isEqualTo(ReservationStatus.CONFIRMED.name)
        assertThat(statusOf(expiredHoldB)).isEqualTo(ReservationStatus.EXPIRED.name)
        assertThat(countSlots(expiredHoldB)).isEqualTo(0)
        assertThat(countSlots(reservationA)).isEqualTo(4)
    }

    private fun capture(task: Runnable): Throwable? {
        try {
            task.run()
            return null
        } catch (failure: Throwable) {
            return failure
        }
    }
}
