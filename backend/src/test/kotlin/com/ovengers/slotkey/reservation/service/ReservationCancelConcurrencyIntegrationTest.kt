package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.credit.service.CreditServiceImpl
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationSlot
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

/**
 * 취소(ReservationCancelService)와 연장(ReservationExtendService), 취소와 신규 HOLD(ReservationHoldService) 간
 * 동시 경합 시 자원 획득 순서(Member-before-Slot) 일치로 인한 데드락(MySQL 1213) 방지 통합 테스트.
 */
class ReservationCancelConcurrencyIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var cancelService: ReservationCancelService

    @Autowired
    private lateinit var extendService: ReservationExtendService

    @Autowired
    private lateinit var holdService: ReservationHoldService

    @Autowired
    private lateinit var adminReservationService: AdminReservationService

    @SpyBean
    private lateinit var creditService: CreditServiceImpl

    @Test
    @DisplayName("동일 회원의 예약A 취소(15~16시)와 예약B 연장(14~15시 -> 15~16시) 동시 경합 시 데드락 없이 모두 정상 완료")
    fun cancelAndExtendOtherReservation_mustNotDeadlockWithValidExtension() {
        val memberId = createMember(100_000)
        val spaceId = createSpace()
        val t14 = tomorrowAt(14, 0)
        val t15 = tomorrowAt(15, 0)
        val t16 = tomorrowAt(16, 0)

        // 예약 B: 14:00~15:00 (연장 대상)
        val reservationB = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, t14, t15)
        // 예약 A: 15:00~16:00 (취소 대상)
        val reservationA = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, t15, t16)

        val cancelReachedRefund = CountDownLatch(1)
        val resumeCancel = CountDownLatch(1)

        // 취소가 Reservation A UPDATE -> Member X 잠금 -> Slot A DELETE를 마친 후 creditService.refund 진입 시점에 대기
        doAnswer({ invocation ->
            cancelReachedRefund.countDown()
            assertThat(resumeCancel.await(10, TimeUnit.SECONDS)).isTrue()
            return@doAnswer invocation.callRealMethod()
        }).`when`(creditService).refund(eqNonNull(memberId), eqNonNull(reservationA), anyInt())

        // 연장이 memberRepository.findByIdForUpdate 호출에 진입함을 관측하기 위한 프록시 설정
        val extendEnteredMemberLock = CountDownLatch(1)
        val originalRepo =
            ReflectionTestUtils.getField(
                extendService,
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
                        extendEnteredMemberLock.countDown()
                    }
                    try {
                        return@newProxyInstance method.invoke(originalRepo, *args.orEmpty())
                    } catch (e: InvocationTargetException) {
                        throw e.cause!!
                    }
                },
            ) as MemberRepository
        ReflectionTestUtils.setField(extendService, "memberRepository", proxyRepo)

        val executor = Executors.newFixedThreadPool(2)
        try {
            // Thread 1: 예약 A 취소 시작
            val cancelFuture = executor.submit<Throwable?>({ capture({ cancelService.cancel(memberId, reservationA) }) })

            // 취소가 Member X 잠금을 쥐고 Slot DELETE를 완료한 뒤 환불 직전 단계에 도달했음을 확인
            assertThat(cancelReachedRefund.await(10, TimeUnit.SECONDS)).`as`("취소가 환불 단계에 도달해야 한다").isTrue()

            // Thread 2: 동일 회원의 다른 예약 B 연장 요청 (15:00~16:00 구간으로 연장 시도)
            val extendFuture = executor.submit<Throwable?>({ capture({ extendService.extend(memberId, reservationB, t15, t16) }) })

            // 연장이 Member X 잠금 획득 호출에 진입했음을 확인 (취소가 Member X를 쥐고 있으므로 대기)
            assertThat(extendEnteredMemberLock.await(5, TimeUnit.SECONDS))
                .`as`("연장이 회원 잠금 획득 호출에 진입해야 한다")
                .isTrue()
            // 취소 트랜잭션이 아직 커밋되지 않았으므로 연장이 완료되지 못하고 대기 중임을 실증
            assertThat(extendFuture.isDone()).`as`("취소가 커밋되기 전에는 연장이 완료되지 않아야 한다").isFalse()

            // 취소 재개 및 커밋 허용 (Member 잠금 해제)
            resumeCancel.countDown()

            val cancelFailure = cancelFuture.get(15, TimeUnit.SECONDS)
            val extendFailure = extendFuture.get(15, TimeUnit.SECONDS)

            // 1. 취소는 데드락 없이 반드시 성공해야 함
            assertThat(cancelFailure).`as`("취소는 성공해야 한다").isNull()

            // 2. 연장은 취소가 슬롯을 반환하고 커밋한 후 데드락 없이 성공해야 함
            assertThat(extendFailure).`as`("연장은 데드락 없이 성공해야 한다").isNull()

            // 3. DB 정합성 검증
            // 예약 A: CANCELLED, 슬롯 0개, 환불 원장 1건
            assertThat(statusOf(reservationA)).isEqualTo(ReservationStatus.CANCELLED.name)
            assertThat(countSlots(reservationA)).isEqualTo(0)
            assertThat(countLedger(reservationA, "REFUND")).isEqualTo(1)
            assertThat(countOutboxEvents(reservationA)).isEqualTo(1)

            // 예약 B: CONFIRMED, endTime 16:00, 슬롯 4개, 차감 원장 1건
            assertThat(statusOf(reservationB)).isEqualTo(ReservationStatus.CONFIRMED.name)
            assertThat(reservationRepository.findById(reservationB).orElseThrow().getEndTime()).isEqualTo(t16)
            assertThat(countSlots(reservationB)).isEqualTo(4)
            assertThat(countLedger(reservationB, "RESERVATION_CHARGE")).isEqualTo(1)

            // 회원 잔액: 100,000 (환불 +10,000, 연장 차감 -10,000)
            assertThat(balanceOf(memberId)).isEqualTo(100_000)
        } finally {
            ReflectionTestUtils.setField(extendService, "memberRepository", originalRepo)
            resumeCancel.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("동일 회원의 예약 취소(15~16시)와 동일 구간 대체 HOLD(15~16시) 동시 경합 시 데드락 없이 모두 정상 완료")
    fun cancelAndHoldSameSlot_mustNotDeadlockWithValidHold() {
        val memberId = createMember(100_000)
        val spaceId = createSpace()
        val t15 = tomorrowAt(15, 0)
        val t16 = tomorrowAt(16, 0)

        // 예약 A: 15:00~16:00 (취소 대상)
        val reservationA = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, t15, t16)

        val cancelReachedRefund = CountDownLatch(1)
        val resumeCancel = CountDownLatch(1)

        // 취소가 Reservation A UPDATE -> Member X 잠금 -> Slot A DELETE를 마친 후 creditService.refund 진입 시점에 대기
        doAnswer({ invocation ->
            cancelReachedRefund.countDown()
            assertThat(resumeCancel.await(10, TimeUnit.SECONDS)).isTrue()
            return@doAnswer invocation.callRealMethod()
        }).`when`(creditService).refund(eqNonNull(memberId), eqNonNull(reservationA), anyInt())

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
            // Thread 1: 예약 A 취소 시작
            val cancelFuture = executor.submit<Throwable?>({ capture({ cancelService.cancel(memberId, reservationA) }) })

            // 취소가 Member X 잠금을 쥐고 Slot DELETE를 완료한 뒤 환불 단계에 도달했음을 확인
            assertThat(cancelReachedRefund.await(10, TimeUnit.SECONDS)).`as`("취소가 환불 단계에 도달해야 한다").isTrue()

            // Thread 2: 동일 회원의 동일 구간(15:00~16:00) 신규 HOLD 요청
            val holdResponseHolder = arrayOfNulls<ReservationResponse>(1)
            val holdFuture =
                executor.submit<Throwable?>({
                    capture({
                        holdResponseHolder[0] = holdService.createHold(memberId, spaceId, t15, t16)
                    })
                })

            // HOLD가 Member X 잠금 획득 호출에 진입했음을 확인 (취소가 Member X를 쥐고 있으므로 대기)
            assertThat(holdEnteredMemberLock.await(5, TimeUnit.SECONDS))
                .`as`("HOLD가 회원 잠금 획득 호출에 진입해야 한다")
                .isTrue()
            // 취소 트랜잭션이 아직 커밋되지 않았으므로 HOLD가 완료되지 못하고 대기 중임을 실증
            assertThat(holdFuture.isDone()).`as`("취소가 커밋되기 전에는 HOLD가 완료되지 않아야 한다").isFalse()

            // 취소 재개 및 커밋 허용 (Member 잠금 해제)
            resumeCancel.countDown()

            val cancelFailure = cancelFuture.get(15, TimeUnit.SECONDS)
            val holdFailure = holdFuture.get(15, TimeUnit.SECONDS)

            // 1. 취소는 데드락 없이 반드시 성공해야 함
            assertThat(cancelFailure).`as`("취소는 성공해야 한다").isNull()

            // 2. HOLD는 취소가 슬롯을 반환하고 커밋한 후 데드락 없이 정상 성공해야 함
            assertThat(holdFailure).`as`("대체 HOLD는 데드락 없이 성공해야 한다").isNull()
            assertThat(holdResponseHolder[0]).isNotNull()
            val newHoldId = holdResponseHolder[0]!!.reservationId

            // 3. DB 정합성 검증
            // 예약 A: CANCELLED, 슬롯 0개, 환불 원장 1건
            assertThat(statusOf(reservationA)).isEqualTo(ReservationStatus.CANCELLED.name)
            assertThat(countSlots(reservationA)).isEqualTo(0)
            assertThat(countLedger(reservationA, "REFUND")).isEqualTo(1)
            assertThat(countOutboxEvents(reservationA)).isEqualTo(1)

            // 신규 HOLD: HELD, 슬롯 2개
            assertThat(statusOf(newHoldId)).isEqualTo(ReservationStatus.HELD.name)
            assertThat(countSlots(newHoldId)).isEqualTo(2)

            // 회원 잔액: 110,000 (환불 +10,000)
            assertThat(balanceOf(memberId)).isEqualTo(110_000)
        } finally {
            ReflectionTestUtils.setField(holdService, "memberRepository", originalRepo)
            resumeCancel.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("다른 회원의 만료 HELD 관리자 강제 취소(Space X 선점)와 동일 구간 신규 HOLD 동시 경합 시 순서 고정 하에 양측 완료")
    fun adminForceCancelExpiredHeldAndHoldSameSlot_mustNotDeadlockWithValidHold() {
        val memberId = createMember(100_000)
        val adminMemberId = createMember(0)
        val spaceId = createSpace()
        val t15 = tomorrowAt(15, 0)
        val t16 = tomorrowAt(16, 0)

        val now = now()
        // 만료된 HELD 예약 A (holdExpiresAt < now)
        val reservation =
            reservationRepository.save(
                Reservation
                    .builder()
                    .memberId(memberId)
                    .spaceId(spaceId)
                    .startTime(t15)
                    .endTime(t16)
                    .status(ReservationStatus.HELD)
                    .pricePerSlotSnapshot(PRICE_PER_SLOT)
                    .totalAmount(PRICE_PER_SLOT * 2)
                    .holdExpiresAt(now.minusMinutes(1))
                    .createdAt(now.minusMinutes(11))
                    .build(),
            )
        var slot = t15
        while (slot.isBefore(t16)) {
            reservationSlotRepository.save(ReservationSlot.of(reservation.getId(), spaceId, slot))
            slot = slot.plusMinutes(30)
        }
        val expiredHeldId = reservation.getId()

        val adminReachedHistory = CountDownLatch(1)
        val resumeAdmin = CountDownLatch(1)
        val holdEnteredSpace = CountDownLatch(1)

        val originalHoldSpaceRepo =
            ReflectionTestUtils.getField(
                holdService,
                "spaceRepository",
            ) as com.ovengers.slotkey.space.repository.SpaceRepository
        val proxyHoldSpaceRepo =
            Proxy.newProxyInstance(
                com.ovengers.slotkey.space.repository.SpaceRepository::class.java.getClassLoader(),
                arrayOf(com.ovengers.slotkey.space.repository.SpaceRepository::class.java),
                { proxy, method, args ->
                    if ("findByIdForShare".equals(method.getName()) && (spaceId == args!![0])) {
                        holdEnteredSpace.countDown()
                    }
                    try {
                        return@newProxyInstance method.invoke(originalHoldSpaceRepo, *args.orEmpty())
                    } catch (e: InvocationTargetException) {
                        throw e.cause!!
                    }
                },
            ) as com.ovengers.slotkey.space.repository.SpaceRepository
        ReflectionTestUtils.setField(holdService, "spaceRepository", proxyHoldSpaceRepo)

        val originalAdminHistoryRepo =
            ReflectionTestUtils.getField(
                adminReservationService,
                "statusHistoryRepository",
            ) as com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
        val proxyAdminHistoryRepo =
            Proxy.newProxyInstance(
                com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository::class.java.getClassLoader(),
                arrayOf(com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository::class.java),
                { proxy, method, args ->
                    if ("save".equals(method.getName())) {
                        adminReachedHistory.countDown()
                        if (!resumeAdmin.await(10, TimeUnit.SECONDS)) {
                            throw RuntimeException("admin resume timeout")
                        }
                    }
                    try {
                        return@newProxyInstance method.invoke(originalAdminHistoryRepo, *args.orEmpty())
                    } catch (e: InvocationTargetException) {
                        throw e.cause!!
                    }
                },
            ) as com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
        ReflectionTestUtils.setField(adminReservationService, "statusHistoryRepository", proxyAdminHistoryRepo)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val holdResponseHolder = arrayOfNulls<ReservationResponse>(1)

            // Thread 1: 관리자 만료 HELD 강제 취소 시작 (Space X -> Reservation X 획득 후 이력 저장 직전 대기)
            val adminFuture =
                executor.submit<Throwable?>({
                    capture({ adminReservationService.forceCancel(expiredHeldId, "관리자 만료 취소", adminMemberId) })
                })

            assertThat(adminReachedHistory.await(10, TimeUnit.SECONDS))
                .`as`("관리자 취소가 Space X 및 Reservation X 획득 후 이력 저장 단계에 도달해야 한다")
                .isTrue()

            // Thread 2: 동일 구간 신규 대체 HOLD 요청
            val holdFuture =
                executor.submit<Throwable?>({
                    capture({
                        holdResponseHolder[0] = holdService.createHold(memberId, spaceId, t15, t16)
                    })
                })

            // HOLD가 Space 공유 잠금 획득 호출에 진입했음을 확인 (관리자가 Space X를 쥐고 있으므로 대기)
            assertThat(holdEnteredSpace.await(5, TimeUnit.SECONDS))
                .`as`("HOLD가 Space 공유 잠금 획득 호출에 진입해야 한다")
                .isTrue()
            // 관리자 트랜잭션이 아직 커밋되지 않았으므로 HOLD가 완료되지 못하고 대기 중임을 실증
            assertThat(holdFuture.isDone()).`as`("관리자가 커밋되기 전에는 HOLD가 완료되지 않아야 한다").isFalse()

            // 관리자 재개 및 커밋 (Space X 해제)
            resumeAdmin.countDown()

            val adminFailure = adminFuture.get(15, TimeUnit.SECONDS)
            val holdFailure = holdFuture.get(15, TimeUnit.SECONDS)

            // 두 스레드 모두 데드락 없이 완료되어야 함
            assertThat(adminFailure).`as`("관리자 강제 취소는 성공해야 한다").isNull()
            assertThat(holdFailure).`as`("대체 HOLD는 데드락 없이 성공해야 한다").isNull()

            // 최종 DB 상태 검증
            // 만료된 기존 예약: CANCELLED, 슬롯 0개
            assertThat(statusOf(expiredHeldId)).isEqualTo(ReservationStatus.CANCELLED.name)
            assertThat(countSlots(expiredHeldId)).isEqualTo(0)
            assertThat(countOutboxEvents(expiredHeldId)).isEqualTo(1)

            // 신규 HOLD: HELD, 슬롯 2개
            assertThat(holdResponseHolder[0]).isNotNull()
            val newHoldId = holdResponseHolder[0]!!.reservationId
            assertThat(statusOf(newHoldId)).isEqualTo(ReservationStatus.HELD.name)
            assertThat(countSlots(newHoldId)).isEqualTo(2)

            // 회원 잔액: 100,000 (HELD는 환불이 없으므로 불변)
            assertThat(balanceOf(memberId)).isEqualTo(100_000)
        } finally {
            ReflectionTestUtils.setField(holdService, "spaceRepository", originalHoldSpaceRepo)
            ReflectionTestUtils.setField(adminReservationService, "statusHistoryRepository", originalAdminHistoryRepo)
            resumeAdmin.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("관리자 자신의 만료 HELD 강제 취소와 신규 HOLD 경합 시 Space X 선점으로 이력 FK S-lock 데드락 없이 양측 완료")
    fun adminForceCancelOwnExpiredHeldAndHoldSameSlot_mustNotDeadlockWithValidHold() {
        // 관리자 자신이 예약자인 케이스 (adminMemberId == memberId)
        val adminMemberId = createMember(100_000)
        val spaceId = createSpace()
        val t15 = tomorrowAt(15, 0)
        val t16 = tomorrowAt(16, 0)

        val now = now()
        // 관리자 자신의 만료된 HELD 예약 A
        val reservation =
            reservationRepository.save(
                Reservation
                    .builder()
                    .memberId(adminMemberId)
                    .spaceId(spaceId)
                    .startTime(t15)
                    .endTime(t16)
                    .status(ReservationStatus.HELD)
                    .pricePerSlotSnapshot(PRICE_PER_SLOT)
                    .totalAmount(PRICE_PER_SLOT * 2)
                    .holdExpiresAt(now.minusMinutes(1))
                    .createdAt(now.minusMinutes(11))
                    .build(),
            )
        var slot = t15
        while (slot.isBefore(t16)) {
            reservationSlotRepository.save(ReservationSlot.of(reservation.getId(), spaceId, slot))
            slot = slot.plusMinutes(30)
        }
        val expiredHeldId = reservation.getId()

        val adminReachedHistory = CountDownLatch(1)
        val resumeAdmin = CountDownLatch(1)
        val holdEnteredSpace = CountDownLatch(1)

        val originalHoldSpaceRepo =
            ReflectionTestUtils.getField(
                holdService,
                "spaceRepository",
            ) as com.ovengers.slotkey.space.repository.SpaceRepository
        val proxyHoldSpaceRepo =
            Proxy.newProxyInstance(
                com.ovengers.slotkey.space.repository.SpaceRepository::class.java.getClassLoader(),
                arrayOf(com.ovengers.slotkey.space.repository.SpaceRepository::class.java),
                { proxy, method, args ->
                    if ("findByIdForShare".equals(method.getName()) && (spaceId == args!![0])) {
                        holdEnteredSpace.countDown()
                    }
                    try {
                        return@newProxyInstance method.invoke(originalHoldSpaceRepo, *args.orEmpty())
                    } catch (e: InvocationTargetException) {
                        throw e.cause!!
                    }
                },
            ) as com.ovengers.slotkey.space.repository.SpaceRepository
        ReflectionTestUtils.setField(holdService, "spaceRepository", proxyHoldSpaceRepo)

        val originalAdminHistoryRepo =
            ReflectionTestUtils.getField(
                adminReservationService,
                "statusHistoryRepository",
            ) as com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
        val proxyAdminHistoryRepo =
            Proxy.newProxyInstance(
                com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository::class.java.getClassLoader(),
                arrayOf(com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository::class.java),
                { proxy, method, args ->
                    if ("save".equals(method.getName())) {
                        adminReachedHistory.countDown()
                        if (!resumeAdmin.await(10, TimeUnit.SECONDS)) {
                            throw RuntimeException("admin resume timeout")
                        }
                    }
                    try {
                        return@newProxyInstance method.invoke(originalAdminHistoryRepo, *args.orEmpty())
                    } catch (e: InvocationTargetException) {
                        throw e.cause!!
                    }
                },
            ) as com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
        ReflectionTestUtils.setField(adminReservationService, "statusHistoryRepository", proxyAdminHistoryRepo)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val holdResponseHolder = arrayOfNulls<ReservationResponse>(1)

            // Thread 1: 관리자 자신의 만료 HELD 강제 취소 시작 (Space X -> Reservation X 획득 후 이력 저장 직전 대기)
            val adminFuture =
                executor.submit<Throwable?>({
                    capture({ adminReservationService.forceCancel(expiredHeldId, "자기 예약 취소", adminMemberId) })
                })

            assertThat(adminReachedHistory.await(10, TimeUnit.SECONDS))
                .`as`("관리자 취소가 Space X 및 Reservation X 획득 후 이력 저장 단계에 도달해야 한다")
                .isTrue()

            // Thread 2: 동일 회원(관리자 본인)의 동일 구간 신규 HOLD 요청
            val holdFuture =
                executor.submit<Throwable?>({
                    capture({
                        holdResponseHolder[0] = holdService.createHold(adminMemberId, spaceId, t15, t16)
                    })
                })

            // HOLD가 Space 공유 잠금 획득 호출에 진입했음을 확인 (관리자가 Space X를 쥐고 있으므로 대기)
            assertThat(holdEnteredSpace.await(5, TimeUnit.SECONDS))
                .`as`("HOLD가 Space 공유 잠금 획득 호출에 진입해야 한다")
                .isTrue()
            assertThat(holdFuture.isDone()).`as`("관리자가 커밋되기 전에는 HOLD가 완료되지 않아야 한다").isFalse()

            // 관리자 재개 및 커밋 (이력 저장 시 changedBy=adminMemberId FK Member S-lock 획득 -> 정상 커밋 및 Space X 해제)
            resumeAdmin.countDown()

            val adminFailure = adminFuture.get(15, TimeUnit.SECONDS)
            val holdFailure = holdFuture.get(15, TimeUnit.SECONDS)

            // 두 스레드 모두 데드락 없이 완료되어야 함
            assertThat(adminFailure).`as`("관리자 자기 예약 강제 취소는 성공해야 한다").isNull()
            assertThat(holdFailure).`as`("대체 HOLD는 데드락 없이 성공해야 한다").isNull()

            // 최종 DB 상태 검증
            // 만료된 기존 예약: CANCELLED, 슬롯 0개
            assertThat(statusOf(expiredHeldId)).isEqualTo(ReservationStatus.CANCELLED.name)
            assertThat(countSlots(expiredHeldId)).isEqualTo(0)
            assertThat(countOutboxEvents(expiredHeldId)).isEqualTo(1)

            // 신규 HOLD: HELD, 슬롯 2개
            assertThat(holdResponseHolder[0]).isNotNull()
            val newHoldId = holdResponseHolder[0]!!.reservationId
            assertThat(statusOf(newHoldId)).isEqualTo(ReservationStatus.HELD.name)
            assertThat(countSlots(newHoldId)).isEqualTo(2)

            // 회원 잔액: 100,000 불변
            assertThat(balanceOf(adminMemberId)).isEqualTo(100_000)
        } finally {
            ReflectionTestUtils.setField(holdService, "spaceRepository", originalHoldSpaceRepo)
            ReflectionTestUtils.setField(adminReservationService, "statusHistoryRepository", originalAdminHistoryRepo)
            resumeAdmin.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    @DisplayName("동일 회원의 만료 HELD 관리자 강제 취소(Space X 선점)와 동일 구간 다른 예약 연장 동시 경합 시 순서 고정 하에 양측 완료")
    fun adminForceCancelExpiredHeldAndExtendOtherReservation_mustNotDeadlockWithValidExtension() {
        val memberId = createMember(100_000)
        val adminMemberId = createMember(0)
        val spaceId = createSpace()
        val t14 = tomorrowAt(14, 0)
        val t15 = tomorrowAt(15, 0)
        val t16 = tomorrowAt(16, 0)

        val now = now()
        // 예약 B: 14:00~15:00 (연장 대상)
        val reservationB = createReservation(memberId, spaceId, ReservationStatus.CONFIRMED, t14, t15)

        // 만료된 HELD 예약 A: 15:00~16:00 (강제 취소 대상)
        val reservation =
            reservationRepository.save(
                Reservation
                    .builder()
                    .memberId(memberId)
                    .spaceId(spaceId)
                    .startTime(t15)
                    .endTime(t16)
                    .status(ReservationStatus.HELD)
                    .pricePerSlotSnapshot(PRICE_PER_SLOT)
                    .totalAmount(PRICE_PER_SLOT * 2)
                    .holdExpiresAt(now.minusMinutes(1))
                    .createdAt(now.minusMinutes(11))
                    .build(),
            )
        var slot = t15
        while (slot.isBefore(t16)) {
            reservationSlotRepository.save(ReservationSlot.of(reservation.getId(), spaceId, slot))
            slot = slot.plusMinutes(30)
        }
        val expiredHeldId = reservation.getId()

        val adminReachedHistory = CountDownLatch(1)
        val resumeAdmin = CountDownLatch(1)
        val extendEnteredSpace = CountDownLatch(1)

        val originalExtendSpaceRepo =
            ReflectionTestUtils.getField(
                extendService,
                "spaceRepository",
            ) as com.ovengers.slotkey.space.repository.SpaceRepository
        val proxyExtendSpaceRepo =
            Proxy.newProxyInstance(
                com.ovengers.slotkey.space.repository.SpaceRepository::class.java.getClassLoader(),
                arrayOf(com.ovengers.slotkey.space.repository.SpaceRepository::class.java),
                { proxy, method, args ->
                    if ("findByIdForShare".equals(method.getName()) && (spaceId == args!![0])) {
                        extendEnteredSpace.countDown()
                    }
                    try {
                        return@newProxyInstance method.invoke(originalExtendSpaceRepo, *args.orEmpty())
                    } catch (e: InvocationTargetException) {
                        throw e.cause!!
                    }
                },
            ) as com.ovengers.slotkey.space.repository.SpaceRepository
        ReflectionTestUtils.setField(extendService, "spaceRepository", proxyExtendSpaceRepo)

        val originalAdminHistoryRepo =
            ReflectionTestUtils.getField(
                adminReservationService,
                "statusHistoryRepository",
            ) as com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
        val proxyAdminHistoryRepo =
            Proxy.newProxyInstance(
                com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository::class.java.getClassLoader(),
                arrayOf(com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository::class.java),
                { proxy, method, args ->
                    if ("save".equals(method.getName())) {
                        adminReachedHistory.countDown()
                        if (!resumeAdmin.await(10, TimeUnit.SECONDS)) {
                            throw RuntimeException("admin resume timeout")
                        }
                    }
                    try {
                        return@newProxyInstance method.invoke(originalAdminHistoryRepo, *args.orEmpty())
                    } catch (e: InvocationTargetException) {
                        throw e.cause!!
                    }
                },
            ) as com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
        ReflectionTestUtils.setField(adminReservationService, "statusHistoryRepository", proxyAdminHistoryRepo)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val adminFuture =
                executor.submit<Throwable?>({
                    capture({ adminReservationService.forceCancel(expiredHeldId, "관리자 만료 취소", adminMemberId) })
                })

            assertThat(adminReachedHistory.await(10, TimeUnit.SECONDS))
                .`as`("관리자 취소가 Space X 및 Reservation X 획득 후 이력 저장 단계에 도달해야 한다")
                .isTrue()

            val extendFuture = executor.submit<Throwable?>({ capture({ extendService.extend(memberId, reservationB, t15, t16) }) })

            assertThat(extendEnteredSpace.await(5, TimeUnit.SECONDS))
                .`as`("연장이 Space 공유 잠금 획득 호출에 진입해야 한다")
                .isTrue()
            assertThat(extendFuture.isDone()).`as`("관리자가 커밋되기 전에는 연장이 완료되지 않아야 한다").isFalse()

            resumeAdmin.countDown()

            val adminFailure = adminFuture.get(15, TimeUnit.SECONDS)
            val extendFailure = extendFuture.get(15, TimeUnit.SECONDS)

            // 관리자 강제 취소와 연장 모두 정상 성공
            assertThat(adminFailure).`as`("관리자 강제 취소는 성공해야 한다").isNull()
            assertThat(extendFailure).`as`("연장은 데드락 없이 성공해야 한다").isNull()

            // 최종 DB 상태 검증
            // 만료된 기존 예약: CANCELLED, 슬롯 0개
            assertThat(statusOf(expiredHeldId)).isEqualTo(ReservationStatus.CANCELLED.name)
            assertThat(countSlots(expiredHeldId)).isEqualTo(0)
            assertThat(countOutboxEvents(expiredHeldId)).isEqualTo(1)

            // 예약 B: CONFIRMED, 슬롯 4개, 차감 원장 1건
            assertThat(statusOf(reservationB)).isEqualTo(ReservationStatus.CONFIRMED.name)
            assertThat(countSlots(reservationB)).isEqualTo(4)
            assertThat(countLedger(reservationB, "RESERVATION_CHARGE")).isEqualTo(1)

            // 회원 잔액: 90,000 (연장 차감 -10,000, HELD는 환불 없음)
            assertThat(balanceOf(memberId)).isEqualTo(90_000)
        } finally {
            ReflectionTestUtils.setField(extendService, "spaceRepository", originalExtendSpaceRepo)
            ReflectionTestUtils.setField(adminReservationService, "statusHistoryRepository", originalAdminHistoryRepo)
            resumeAdmin.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun countOutboxEvents(reservationId: Long): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE JSON_EXTRACT(payload, '$.reservationId') = ?",
            Int::class.javaObjectType,
            reservationId,
        )!!

    private fun capture(task: Runnable): Throwable? {
        try {
            task.run()
            return null
        } catch (failure: Throwable) {
            return failure
        }
    }
}
