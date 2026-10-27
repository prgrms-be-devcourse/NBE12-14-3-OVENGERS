package com.ovengers.slotkey.reservation.service

import com.ovengers.slotkey.access.entity.DoorAccessToken
import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.entity.ReservationSlot
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatusHistory
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.entity.Space
import com.ovengers.slotkey.space.entity.SpaceStatus
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.FixedClockConfig
import com.ovengers.slotkey.support.IntegrationTestSupport
import com.ovengers.slotkey.support.anyNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Import
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.time.LocalTime

@Import(FixedClockConfig::class)
class AdminReservationServiceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var adminReservationService: AdminReservationService

    @Autowired
    private lateinit var outboxEventRepository: com.ovengers.slotkey.outbox.OutboxEventRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var reservationSlotRepository: ReservationSlotRepository

    @Autowired
    private lateinit var statusHistoryRepository: ReservationStatusHistoryRepository

    @Autowired
    private lateinit var doorAccessTokenRepository: DoorAccessTokenRepository

    @Autowired
    private lateinit var transactionTemplate: org.springframework.transaction.support.TransactionTemplate

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @SpyBean
    private lateinit var auditLogRepository: AuditLogRepository

    private lateinit var userMember: Member
    private lateinit var adminMember: Member
    private lateinit var space: Space
    private lateinit var reservation: Reservation
    private lateinit var doorAccessToken: DoorAccessToken

    @BeforeEach
    fun setUp() {
        outboxEventRepository.deleteAllInBatch()
        creditTransactionRepository.deleteAllInBatch()
        auditLogRepository.deleteAllInBatch()
        doorAccessTokenRepository.deleteAllInBatch()
        statusHistoryRepository.deleteAllInBatch()
        reservationSlotRepository.deleteAllInBatch()
        reservationRepository.deleteAllInBatch()
        spaceRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()

        userMember = memberRepository.save(Member("user@slotkey.test", "예약자"))
        adminMember = Member("admin@slotkey.test", "관리자")
        ReflectionTestUtils.setField(adminMember, "role", MemberRole.ADMIN)
        adminMember = memberRepository.save(adminMember)

        space =
            spaceRepository.save(
                Space
                    .builder()
                    .name("회의실 A")
                    .location("강남")
                    .capacity(4)
                    .pricePerSlot(3000L)
                    .openingTime(LocalTime.of(9, 0))
                    .closingTime(LocalTime.of(22, 0))
                    .status(SpaceStatus.ACTIVE)
                    .version(0)
                    .build(),
            )

        val startTime: LocalDateTime = LocalDateTime.of(2026, 9, 20, 14, 0)
        val endTime: LocalDateTime = LocalDateTime.of(2026, 9, 20, 15, 0)

        reservation =
            reservationRepository.save(
                Reservation
                    .builder()
                    .memberId(userMember.id)
                    .spaceId(space.id)
                    .startTime(startTime)
                    .endTime(endTime)
                    .status(ReservationStatus.CONFIRMED)
                    .pricePerSlotSnapshot(3000)
                    .totalAmount(6000)
                    .createdAt(startTime.minusDays(1))
                    .build(),
            )

        reservationSlotRepository.save(ReservationSlot.of(reservation.getId(), space.id, startTime))
        reservationSlotRepository.save(ReservationSlot.of(reservation.getId(), space.id, startTime.plusMinutes(30)))

        doorAccessToken =
            doorAccessTokenRepository.save(
                DoorAccessToken(
                    reservation,
                    "token-hash-123",
                    startTime.minusHours(1),
                ),
            )
    }

    @Test
    @DisplayName("관리자 예약 강제 취소 도중 감사 로그 저장이 실패하면 예약 상태, 슬롯, 도어 토큰 폐기, 상태 이력이 모두 롤백된다")
    fun forceCancel_auditLogFails_rollsBackReservationSlotsTokenAndHistory() {
        // given
        doThrow(RuntimeException("Audit DB failure"))
            .`when`(auditLogRepository)
            .save(anyNonNull())

        // when & then
        assertThatThrownBy({ adminReservationService.forceCancel(reservation.getId(), "관리자 직권 취소", adminMember.id) })
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("Audit DB failure")

        // 1. 예약 상태 롤백 검증: CONFIRMED 유지, cancelledAt은 null 유지
        val reloadedReservation: Reservation = reservationRepository.findById(reservation.getId()).orElseThrow()
        assertThat(reloadedReservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED)
        assertThat(reloadedReservation.cancelledAt).isNull()

        // 2. 예약 슬롯 롤백 검증: 삭제되지 않고 2개 슬롯이 그대로 남아있음
        assertThat(reservationSlotRepository.count()).isEqualTo(2)

        // 3. 도어 토큰 롤백 검증: revoke 처리되지 않고 활성(revokedAt == null) 상태 유지
        val reloadedToken: DoorAccessToken = doorAccessTokenRepository.findById(doorAccessToken.id).orElseThrow()
        assertThat(reloadedToken.isRevoked).isFalse()
        assertThat(reloadedToken.revokedAt).isNull()

        // 4. 상태 이력 롤백 검증: 저장되지 않고 비어있음
        assertThat(statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(reservation.getId())).isEmpty()

        // 5. 감사 로그 롤백 검증: 저장되지 않음
        assertThat(auditLogRepository.count()).isZero()

        // 6. 회원 잔액 및 크레딧 원장 롤백 검증: 잔액 초기값 유지, REFUND 원장 0건
        val reloadedMember: Member = memberRepository.findById(userMember.id).orElseThrow()
        assertThat(reloadedMember.balance).isEqualTo(userMember.balance)
        assertThat(creditTransactionRepository.count()).isZero()
    }

    @Test
    @DisplayName("관리자 예약 강제 취소가 정상 커밋되면 예약 취소, 슬롯 삭제, 토큰 폐기, 상태 이력 및 감사 로그가 모두 반영된다")
    fun forceCancel_success_commitsAllChangesAndAudit() {
        val expectedNow: LocalDateTime = FixedClockConfig.FIXED_DATE_TIME

        // when
        adminReservationService.forceCancel(reservation.getId(), "정상 직권 취소", adminMember.id)

        // 1. 예약 상태 확인
        val reloadedReservation: Reservation = reservationRepository.findById(reservation.getId()).orElseThrow()
        assertThat(reloadedReservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED)
        assertThat(reloadedReservation.cancelledAt).isEqualTo(expectedNow)

        // 2. 슬롯 삭제 확인
        assertThat(reservationSlotRepository.count()).isZero()

        // 3. 도어 토큰 폐기 확인
        val reloadedToken: DoorAccessToken = doorAccessTokenRepository.findById(doorAccessToken.id).orElseThrow()
        assertThat(reloadedToken.isRevoked).isTrue()
        assertThat(reloadedToken.revokedAt).isEqualTo(expectedNow)
        assertThat(reloadedToken.revokeReason).isEqualTo("ADMIN_FORCE_CANCEL")

        // 4. 상태 이력 확인
        val histories: List<ReservationStatusHistory> =
            statusHistoryRepository.findAllByReservationIdOrderByChangedAtAsc(
                reservation.getId(),
            )
        assertThat(histories).hasSize(1)
        val history: ReservationStatusHistory = histories.get(0)
        assertThat(history.changedByMemberId).isEqualTo(adminMember.id)
        assertThat(history.fromStatus).isEqualTo(ReservationStatus.CONFIRMED)
        assertThat(history.toStatus).isEqualTo(ReservationStatus.CANCELLED)
        assertThat(history.reason).isEqualTo("정상 직권 취소")
        assertThat(history.changedAt).isEqualTo(expectedNow)

        // 5. 감사 로그 확인
        val auditLogs: List<AuditLog> = auditLogRepository.findAll()
        assertThat(auditLogs).hasSize(1)
        val auditLog: AuditLog = auditLogs.get(0)
        assertThat(auditLog.actorMemberId).isEqualTo(adminMember.id)
        assertThat(auditLog.action).isEqualTo(AuditAction.FORCE_CANCEL_RESERVATION)
        assertThat(auditLog.targetType).isEqualTo(AuditTargetType.RESERVATION)
        assertThat(auditLog.targetId).isEqualTo(reservation.getId())
        assertThat(auditLog.reason).isEqualTo("정상 직권 취소")
        assertThat(auditLog.beforeValue).contains("CONFIRMED")
        assertThat(auditLog.afterValue).contains("CANCELLED")
        assertThat(auditLog.createdAt).isEqualTo(expectedNow)

        // 6. 크레딧 환불 확인: 회원 잔액이 totalAmount(6000)만큼 증가하고 REFUND 원장이 정확히 1건 기록됨
        val reloadedUser: Member = memberRepository.findById(userMember.id).orElseThrow()
        assertThat(reloadedUser.balance).isEqualTo(userMember.balance + reservation.getTotalAmount())
        val transactions: List<CreditTransaction> = creditTransactionRepository.findAll()
        assertThat(transactions).hasSize(1)
        val transaction: CreditTransaction = transactions.get(0)
        assertThat(transaction.member.id).isEqualTo(userMember.id)
        assertThat(transaction.reservation!!.getId()).isEqualTo(reservation.getId())
        assertThat(transaction.type).isEqualTo(CreditTransactionType.REFUND)
        assertThat(transaction.amount).isEqualTo(reservation.getTotalAmount())
        assertThat(transaction.balanceAfter).isEqualTo(userMember.balance + reservation.getTotalAmount())
    }

    @Test
    @DisplayName("강제 취소 시 이미 상태가 전이되었으면 조건부 UPDATE가 0행을 반환하여 409 예외가 발생하고 후속 처리가 롤백된다")
    fun forceCancel_whenConcurrentTransitionOccurs_failsWithConflictAndAborts() {
        // given: 다른 트랜잭션이 이미 IN_USE -> COMPLETED로 전이 완료했다고 가정
        transactionTemplate.executeWithoutResult({ status ->
            reservationRepository.checkInIfConfirmed(
                reservation.getId(),
                LocalDateTime.of(2026, 9, 20, 14, 0),
                ReservationStatus.CONFIRMED,
                ReservationStatus.IN_USE,
            )
            reservationRepository.checkOutIfInUse(
                reservation.getId(),
                LocalDateTime.of(2026, 9, 20, 15, 0),
                ReservationStatus.IN_USE,
                ReservationStatus.COMPLETED,
            )
        })

        // when & then: 사전 검사 또는 조건부 UPDATE 단계에서 충돌 감지 (409)
        assertThatThrownBy({ adminReservationService.forceCancel(reservation.getId(), "취소 시도", adminMember.id) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_STATE_CONFLICT)

        // 슬롯은 유지(또는 완료 상태 유지), 감사 로그는 생성되지 않음
        assertThat(auditLogRepository.count()).isZero()
    }
}
