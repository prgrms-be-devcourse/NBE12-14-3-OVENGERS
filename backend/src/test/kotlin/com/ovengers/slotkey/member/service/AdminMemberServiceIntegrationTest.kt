package com.ovengers.slotkey.member.service

import com.ovengers.slotkey.access.repository.DoorAccessTokenRepository
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.auth.entity.AuthSession
import com.ovengers.slotkey.auth.entity.RefreshToken
import com.ovengers.slotkey.auth.repository.AuthSessionRepository
import com.ovengers.slotkey.auth.repository.RefreshTokenRepository
import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.reservation.repository.ReservationSlotRepository
import com.ovengers.slotkey.reservation.repository.ReservationStatusHistoryRepository
import com.ovengers.slotkey.space.repository.SpaceRepository
import com.ovengers.slotkey.support.FixedClockConfig
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Import
import org.springframework.test.util.ReflectionTestUtils

@Import(FixedClockConfig::class)
internal class AdminMemberServiceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var adminMemberService: AdminMemberService

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var authSessionRepository: AuthSessionRepository

    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Autowired
    private lateinit var doorAccessTokenRepository: DoorAccessTokenRepository

    @Autowired
    private lateinit var reservationStatusHistoryRepository: ReservationStatusHistoryRepository

    @Autowired
    private lateinit var reservationSlotRepository: ReservationSlotRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var spaceRepository: SpaceRepository

    @Autowired
    private lateinit var outboxEventRepository: com.ovengers.slotkey.outbox.OutboxEventRepository

    @SpyBean
    private lateinit var auditLogRepository: AuditLogRepository

    private lateinit var userMember: Member
    private lateinit var adminMember: Member

    @BeforeEach fun setUp() {
        outboxEventRepository.deleteAllInBatch()
        refreshTokenRepository.deleteAllInBatch()
        authSessionRepository.deleteAllInBatch()
        auditLogRepository.deleteAllInBatch()
        creditTransactionRepository.deleteAllInBatch()
        doorAccessTokenRepository.deleteAllInBatch()
        reservationStatusHistoryRepository.deleteAllInBatch()
        reservationSlotRepository.deleteAllInBatch()
        reservationRepository.deleteAllInBatch()
        spaceRepository.deleteAllInBatch()
        memberRepository.deleteAllInBatch()

        userMember = memberRepository.save(Member("user@slotkey.test", "일반회원"))
        adminMember = Member("admin@slotkey.test", "관리자")
        ReflectionTestUtils.setField(adminMember, "role", MemberRole.ADMIN)
        adminMember = memberRepository.save(adminMember)
    }

    @Test
    @DisplayName("회원 정지 도중 감사 로그 저장이 실패하면 회원 상태 변경이 롤백되어 ACTIVE를 유지한다")
    fun suspend_auditLogFails_rollsBackMemberStatus() {
        // given
        val session: AuthSession = authSessionService.create(userMember)
        val tokenHash = "a".repeat(64)
        refreshTokenRepository.saveAndFlush(
            RefreshToken(
                userMember,
                session,
                tokenHash,
                FixedClockConfig.FIXED_DATE_TIME.plusHours(24),
            ),
        )

        doThrow(RuntimeException("Audit DB failure"))
            .whenever(auditLogRepository)
            .save(any())

        // when & then
        assertThatThrownBy { adminMemberService.suspend(userMember.id!!, "불량 이용", adminMember.id!!) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("Audit DB failure")

        // DB 재조회로 롤백 검증 (@Transactional 없이 독립 트랜잭션으로 조회)
        val reloaded: Member = memberRepository.findById(userMember.id!!).orElseThrow()
        assertThat(reloaded.status).isEqualTo(MemberStatus.ACTIVE)
        assertThat(authSessionRepository.findById(session.id).orElseThrow().revokedAt)
            .isNull()
        assertThat(refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow().revokedAt)
            .isNull()
        assertThat(auditLogRepository.count()).isZero()
    }

    @Test
    @DisplayName("회원 복구 도중 감사 로그 저장이 실패하면 회원 상태 변경이 롤백되어 SUSPENDED를 유지한다")
    fun restore_auditLogFails_rollsBackMemberStatus() {
        // given
        userMember.updateStatus(MemberStatus.SUSPENDED)
        memberRepository.save(userMember)

        doThrow(RuntimeException("Audit DB failure"))
            .whenever(auditLogRepository)
            .save(any())

        // when & then
        assertThatThrownBy { adminMemberService.restore(userMember.id!!, "정지 해제", adminMember.id!!) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("Audit DB failure")

        // DB 재조회로 롤백 검증
        val reloaded: Member = memberRepository.findById(userMember.id!!).orElseThrow()
        assertThat(reloaded.status).isEqualTo(MemberStatus.SUSPENDED)
        assertThat(auditLogRepository.count()).isZero()
    }

    @Test
    @DisplayName("관리자 크레딧 지급 도중 감사 로그 저장이 실패하면 회원 잔액과 credit_transaction이 모두 롤백된다")
    fun grantCredit_auditLogFails_rollsBackBalanceAndTransaction() {
        // given
        val initialBalance: Int = userMember.balance
        val grantAmount: Int = 50000

        doThrow(RuntimeException("Audit DB failure"))
            .whenever(auditLogRepository)
            .save(any())

        // when & then
        assertThatThrownBy { adminMemberService.grantCredit(userMember.id!!, grantAmount, "보상 지급", adminMember.id!!) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("Audit DB failure")

        // DB 재조회로 롤백 검증
        val reloaded: Member = memberRepository.findById(userMember.id!!).orElseThrow()
        assertThat(reloaded.balance).isEqualTo(initialBalance)
        assertThat(creditTransactionRepository.count()).isZero()
        assertThat(auditLogRepository.count()).isZero()
        assertThat(outboxEventRepository.findAll().filter { it.topic == "notification-events" }).isEmpty()
    }

    @Test
    @DisplayName("회원 정지가 정상 커밋되면 회원 상태가 SUSPENDED로 변경되고 감사 로그가 함께 저장된다")
    fun suspend_success_commitsMemberStatusAndAudit() {
        // when
        adminMemberService.suspend(userMember.id!!, "불량 이용", adminMember.id!!)

        // then: DB 재조회 검증
        val reloaded: Member = memberRepository.findById(userMember.id!!).orElseThrow()
        assertThat(reloaded.status).isEqualTo(MemberStatus.SUSPENDED)

        val auditLogs: List<AuditLog> = auditLogRepository.findAll()
        assertThat(auditLogs).hasSize(1)
        val auditLog: AuditLog = auditLogs[0]
        assertThat(auditLog.actorMemberId).isEqualTo(adminMember.id!!)
        assertThat(auditLog.action).isEqualTo(AuditAction.SUSPEND_MEMBER)
        assertThat(auditLog.targetType).isEqualTo(AuditTargetType.MEMBER)
        assertThat(auditLog.targetId).isEqualTo(userMember.id!!)
        assertThat(auditLog.reason).isEqualTo("불량 이용")
        assertThat(auditLog.beforeValue).contains("ACTIVE")
        assertThat(auditLog.afterValue).contains("SUSPENDED")
        assertThat(auditLog.createdAt).isEqualTo(FixedClockConfig.FIXED_DATE_TIME)
    }

    @Test
    @DisplayName("회원 복구가 정상 커밋되면 회원 상태가 ACTIVE로 복구되고 감사 로그가 함께 저장된다")
    fun restore_success_commitsMemberStatusAndAudit() {
        // given
        userMember.updateStatus(MemberStatus.SUSPENDED)
        memberRepository.save(userMember)

        // when
        adminMemberService.restore(userMember.id!!, "정지 해제", adminMember.id!!)

        // then: DB 재조회 검증
        val reloaded: Member = memberRepository.findById(userMember.id!!).orElseThrow()
        assertThat(reloaded.status).isEqualTo(MemberStatus.ACTIVE)

        val auditLogs: List<AuditLog> = auditLogRepository.findAll()
        assertThat(auditLogs).hasSize(1)
        val auditLog: AuditLog = auditLogs[0]
        assertThat(auditLog.actorMemberId).isEqualTo(adminMember.id!!)
        assertThat(auditLog.action).isEqualTo(AuditAction.REACTIVATE_MEMBER)
        assertThat(auditLog.targetType).isEqualTo(AuditTargetType.MEMBER)
        assertThat(auditLog.targetId).isEqualTo(userMember.id!!)
        assertThat(auditLog.reason).isEqualTo("정지 해제")
        assertThat(auditLog.beforeValue).contains("SUSPENDED")
        assertThat(auditLog.afterValue).contains("ACTIVE")
        assertThat(auditLog.createdAt).isEqualTo(FixedClockConfig.FIXED_DATE_TIME)
    }

    @Test
    @DisplayName("관리자 크레딧 지급이 정상 커밋되면 잔액과 credit_transaction, 감사 로그가 모두 함께 저장된다")
    fun grantCredit_success_commitsBalanceTransactionAndAudit() {
        // when
        adminMemberService.grantCredit(userMember.id!!, 50000, "보상 지급", adminMember.id!!)

        // then: DB 재조회 검증
        val reloaded: Member = memberRepository.findById(userMember.id!!).orElseThrow()
        assertThat(reloaded.balance).isEqualTo(50000)

        val txs: List<CreditTransaction> = creditTransactionRepository.findAll()
        assertThat(txs).hasSize(1)
        val tx: CreditTransaction = txs[0]
        assertThat(tx.amount).isEqualTo(50000)
        assertThat(tx.balanceAfter).isEqualTo(50000)
        assertThat(tx.type).isEqualTo(CreditTransactionType.ADMIN_GRANT)
        assertThat(tx.member.id).isEqualTo(userMember.id!!)

        val auditLogs: List<AuditLog> = auditLogRepository.findAll()
        assertThat(auditLogs).hasSize(1)
        val auditLog: AuditLog = auditLogs[0]
        assertThat(auditLog.actorMemberId).isEqualTo(adminMember.id!!)
        assertThat(auditLog.action).isEqualTo(AuditAction.GRANT_CREDIT)
        assertThat(auditLog.targetType).isEqualTo(AuditTargetType.MEMBER)
        assertThat(auditLog.targetId).isEqualTo(userMember.id!!)
        assertThat(auditLog.reason).isEqualTo("보상 지급")
        assertThat(auditLog.createdAt).isEqualTo(FixedClockConfig.FIXED_DATE_TIME)

        // 알림 이벤트: 지급받은 회원 앞으로 PENDING 1건, 문구에 금액·잔액·사유가 들어간다
        val events = outboxEventRepository.findAll().filter { it.topic == "notification-events" }
        assertThat(events).hasSize(1)
        val event = events.single()
        assertThat(event.eventType).isEqualTo("CREDIT_GRANTED")
        assertThat(event.partitionKey).isEqualTo(userMember.id.toString())
        val payload =
            com.fasterxml.jackson.databind
                .ObjectMapper()
                .readTree(event.payload)
        assertThat(payload["recipientMemberId"].asLong()).isEqualTo(userMember.id)
        assertThat(payload["referenceId"].asLong()).isEqualTo(userMember.id)
        assertThat(payload["message"].asText()).contains("50,000원", "보상 지급")
    }
}
