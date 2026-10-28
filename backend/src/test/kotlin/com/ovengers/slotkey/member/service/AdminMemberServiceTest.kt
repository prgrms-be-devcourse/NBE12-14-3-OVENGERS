@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.member.service

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditLog
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.audit.service.AuditLogService
import com.ovengers.slotkey.auth.service.AuthSessionService
import com.ovengers.slotkey.auth.service.VerificationGuardService
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.credit.service.CreditGrantService
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.dto.response.AdminMemberResponse
import com.ovengers.slotkey.member.dto.response.MemberStatusChangeResponse
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.notification.entity.NotificationType
import com.ovengers.slotkey.outbox.NotificationEventRecorder
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import com.ovengers.slotkey.space.repository.SpaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional

@ExtendWith(MockitoExtension::class)
internal class AdminMemberServiceTest {
    @Mock
    private lateinit var memberRepository: MemberRepository

    @Mock
    private lateinit var auditLogRepository: AuditLogRepository

    @Mock
    private lateinit var auditLogService: AuditLogService

    @Mock
    private lateinit var creditGrantService: CreditGrantService

    @Mock
    private lateinit var notificationEventRecorder: NotificationEventRecorder

    @Mock
    private lateinit var verificationGuardService: VerificationGuardService

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Mock
    private lateinit var spaceRepository: SpaceRepository

    @Mock
    private lateinit var authSessionService: AuthSessionService

    private lateinit var clock: Clock
    private lateinit var adminMemberService: AdminMemberService

    @BeforeEach fun setUp() {
        clock =
            Clock.fixed(
                Instant.parse("2026-09-18T04:00:00Z"),
                ZoneId.of("Asia/Seoul"),
            )

        adminMemberService =
            AdminMemberService(
                memberRepository,
                auditLogRepository,
                auditLogService,
                creditGrantService,
                clock,
                verificationGuardService,
                reservationRepository,
                creditTransactionRepository,
                spaceRepository,
                authSessionService,
                notificationEventRecorder,
            )
    }

    @Test
    @DisplayName("회원 검색 결과가 없으면 빈 페이지를 반환하고 상태 변경 이력을 조회하지 않는다")
    fun getMembers_emptyResult_returnsEmptyPage() {
        // given
        val pageable: Pageable = PageRequest.of(0, 20)

        whenever(
            memberRepository.searchMembers(
                null,
                null,
                pageable,
            ),
        ).thenReturn(
            PageImpl(
                emptyList(),
                pageable,
                0,
            ),
        )

        // when
        val result: Page<AdminMemberResponse> =
            adminMemberService.getMembers(
                null,
                null,
                pageable,
            )

        // then
        assertThat(result.content).isEmpty()
        assertThat(result.totalElements).isZero()
        verifyNoInteractions(auditLogRepository)
    }

    @Test
    @DisplayName("회원 목록 조회 시 최근 상태 변경 정보까지 응답한다")
    fun getMembers_success() {
        // given
        val memberId: Long = 1L
        val pageable: Pageable = PageRequest.of(0, 20)

        val member: Member = mock()
        val auditLog: AuditLog = mock()

        val createdAt: LocalDateTime =
            LocalDateTime.of(2026, 9, 1, 10, 0)

        val lastStatusChangedAt: LocalDateTime? =
            LocalDateTime.of(2026, 9, 17, 15, 0)

        whenever(member.id).thenReturn(memberId)
        whenever(member.email).thenReturn("user@test.com")
        whenever(member.nickname).thenReturn("테스트회원")
        whenever(member.role).thenReturn(MemberRole.USER)
        whenever(member.status).thenReturn(MemberStatus.SUSPENDED)
        whenever(member.balance).thenReturn(10000)
        whenever(member.createdAt).thenReturn(createdAt)

        whenever(
            memberRepository.searchMembers(
                "user",
                MemberStatus.SUSPENDED,
                pageable,
            ),
        ).thenReturn(
            PageImpl(
                listOf(member),
                pageable,
                1,
            ),
        )

        whenever(
            auditLogRepository
                .findFirstByTargetTypeAndTargetIdAndActionInOrderByCreatedAtDescIdDesc(
                    AuditTargetType.MEMBER,
                    memberId,
                    listOf(
                        AuditAction.SUSPEND_MEMBER,
                        AuditAction.REACTIVATE_MEMBER,
                        AuditAction.LOCK_MEMBER_BY_VERIFICATION,
                    ),
                ),
        ).thenReturn(Optional.of(auditLog))
        whenever(auditLog.createdAt).thenReturn(lastStatusChangedAt)
        whenever(auditLog.reason).thenReturn("운영 정책 위반")

        // when
        val result: Page<AdminMemberResponse> =
            adminMemberService.getMembers(
                MemberStatus.SUSPENDED,
                "user",
                pageable,
            )

        // then
        assertThat(result.totalElements).isEqualTo(1)

        val response: AdminMemberResponse = result.content[0]

        assertThat(response.memberId).isEqualTo(memberId)
        assertThat(response.email).isEqualTo("user@test.com")
        assertThat(response.status).isEqualTo(MemberStatus.SUSPENDED)
        assertThat(response.lastStatusChangedAt).isEqualTo(lastStatusChangedAt)
        assertThat(response.lastStatusChangeReason).isEqualTo("운영 정책 위반")
    }

    @Test
    @DisplayName("일반 회원을 정지하면 상태를 변경하고 감사 로그를 기록한다")
    fun suspend_success() {
        // given
        val memberId: Long = 1L
        val adminMemberId: Long = 100L
        val reason = "운영 정책 위반"

        val member: Member = mock()

        whenever(memberRepository.findByIdForUpdate(memberId)).thenReturn(Optional.of(member))
        whenever(member.id).thenReturn(memberId)
        whenever(member.role).thenReturn(MemberRole.USER)

        // WITHDRAWN 검사, beforeStatus 추출, response.status
        whenever(member.status)
            .thenReturn(
                MemberStatus.ACTIVE,
                MemberStatus.ACTIVE,
                MemberStatus.SUSPENDED,
            )

        // when
        val response: MemberStatusChangeResponse =
            adminMemberService.suspend(
                memberId,
                reason,
                adminMemberId,
            )

        // then
        verify(member).updateStatus(MemberStatus.SUSPENDED)
        verify(authSessionService).revokeAllForMember(
            memberId,
            LocalDateTime.of(2026, 9, 18, 13, 0),
        )

        verify(auditLogService)
            .log(
                adminMemberId,
                AuditAction.SUSPEND_MEMBER,
                AuditTargetType.MEMBER,
                memberId,
                reason,
                MemberStatus.ACTIVE,
                MemberStatus.SUSPENDED,
            )

        assertThat(response.memberId).isEqualTo(memberId)
        assertThat(response.status).isEqualTo(MemberStatus.SUSPENDED)
        assertThat(response.reason).isEqualTo(reason)
        assertThat(response.changedAt)
            .isEqualTo(
                LocalDateTime.of(
                    2026,
                    9,
                    18,
                    13,
                    0,
                ),
            )
    }

    @Test
    @DisplayName("정지 회원을 복구하면 ACTIVE 상태로 변경하고 감사 로그를 기록한다")
    fun restore_success() {
        // given
        val memberId: Long = 1L
        val adminMemberId: Long = 100L
        val reason = "정지 사유 해소"

        val member: Member = mock()

        whenever(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        whenever(memberRepository.findByIdForUpdate(memberId)).thenReturn(Optional.of(member))
        whenever(member.id).thenReturn(memberId)
        whenever(member.role).thenReturn(MemberRole.USER)
        whenever(member.status)
            .thenReturn(
                MemberStatus.SUSPENDED,
                MemberStatus.SUSPENDED,
                MemberStatus.SUSPENDED,
                MemberStatus.ACTIVE,
            )

        // when
        val response: MemberStatusChangeResponse =
            adminMemberService.restore(
                memberId,
                reason,
                adminMemberId,
            )

        // then
        verify(member).updateStatus(MemberStatus.ACTIVE)

        verify(auditLogService)
            .log(
                adminMemberId,
                AuditAction.REACTIVATE_MEMBER,
                AuditTargetType.MEMBER,
                memberId,
                reason,
                MemberStatus.SUSPENDED,
                MemberStatus.ACTIVE,
            )

        assertThat(response.status).isEqualTo(MemberStatus.ACTIVE)
    }

    @Test
    @DisplayName("관리자 계정은 정지할 수 없다")
    fun suspend_targetIsAdmin_throwsException() {
        // given
        val memberId: Long = 1L

        val member: Member = mock()

        whenever(memberRepository.findByIdForUpdate(memberId)).thenReturn(Optional.of(member))
        whenever(member.status).thenReturn(MemberStatus.ACTIVE)
        whenever(member.role).thenReturn(MemberRole.ADMIN)

        // when
        val exception: BusinessException =
            assertThrows(
                BusinessException::class.java,
                {
                    adminMemberService.suspend(
                        memberId,
                        "정지 시도",
                        100L,
                    )
                },
            )

        // then
        assertThat(exception.errorCode).isEqualTo(ErrorCode.TARGET_IS_ADMIN)
        verify(member, never()).updateStatus(any())
        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("관리자 계정은 복구 대상이 될 수 없다")
    fun restore_targetIsAdmin_throwsException() {
        // given
        val memberId: Long = 1L

        val member: Member = mock()

        whenever(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        whenever(member.status).thenReturn(MemberStatus.ACTIVE)
        whenever(member.role).thenReturn(MemberRole.ADMIN)

        // when
        val exception: BusinessException =
            assertThrows(
                BusinessException::class.java,
                {
                    adminMemberService.restore(
                        memberId,
                        "복구 시도",
                        100L,
                    )
                },
            )

        // then
        assertThat(exception.errorCode).isEqualTo(ErrorCode.TARGET_IS_ADMIN)
        verify(member, never()).updateStatus(any())
        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("존재하지 않는 회원을 정지하면 MEMBER_NOT_FOUND 예외가 발생한다")
    fun suspend_memberNotFound_throwsException() {
        // given
        val memberId: Long = 999L

        whenever(memberRepository.findByIdForUpdate(memberId))
            .thenReturn(Optional.empty())

        // when
        val exception: BusinessException =
            assertThrows(
                BusinessException::class.java,
                {
                    adminMemberService.suspend(
                        memberId,
                        "정지",
                        100L,
                    )
                },
            )

        // then
        assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.MEMBER_NOT_FOUND)

        verifyNoInteractions(auditLogService)
    }

    @Test
    @DisplayName("관리자가 회원에게 크레딧을 지급하면 지급 서비스와 감사 로그를 호출한다")
    fun grantCredit_success() {
        // given
        val memberId: Long = 1L
        val adminMemberId: Long = 100L
        val amount: Int = 50000
        val reason = "보상 지급"

        val beforeMember: Member = mock()
        val updatedMember: Member = mock()

        whenever(memberRepository.findByIdForUpdate(memberId))
            .thenReturn(Optional.of(beforeMember))
        whenever(beforeMember.status).thenReturn(MemberStatus.ACTIVE)
        whenever(memberRepository.findById(memberId))
            .thenReturn(Optional.of(updatedMember))

        whenever(beforeMember.balance).thenReturn(1000000)
        whenever(updatedMember.id).thenReturn(memberId)
        whenever(updatedMember.email).thenReturn("user@test.com")
        whenever(updatedMember.nickname).thenReturn("일반회원")
        whenever(updatedMember.role).thenReturn(MemberRole.USER)
        whenever(updatedMember.status).thenReturn(MemberStatus.ACTIVE)
        whenever(updatedMember.balance).thenReturn(1050000)

        whenever(updatedMember.createdAt)
            .thenReturn(
                LocalDateTime.of(
                    2026,
                    9,
                    1,
                    10,
                    0,
                ),
            )

        whenever(
            creditGrantService.grantAdminCredit(
                memberId,
                amount,
                reason,
            ),
        ).thenReturn(1050000)

        whenever(
            auditLogRepository
                .findFirstByTargetTypeAndTargetIdAndActionInOrderByCreatedAtDescIdDesc(
                    AuditTargetType.MEMBER,
                    memberId,
                    listOf(
                        AuditAction.SUSPEND_MEMBER,
                        AuditAction.REACTIVATE_MEMBER,
                        AuditAction.LOCK_MEMBER_BY_VERIFICATION,
                    ),
                ),
        ).thenReturn(Optional.empty())

        // when
        val response: AdminMemberResponse =
            adminMemberService.grantCredit(
                memberId,
                amount,
                reason,
                adminMemberId,
            )

        // then
        verify(creditGrantService)
            .grantAdminCredit(
                memberId,
                amount,
                reason,
            )

        verify(auditLogService)
            .log(
                adminMemberId,
                AuditAction.GRANT_CREDIT,
                AuditTargetType.MEMBER,
                memberId,
                reason,
                1000000,
                1050000,
            )

        verify(notificationEventRecorder)
            .record(
                eq(NotificationType.CREDIT_GRANTED),
                eq(memberId),
                eq(memberId),
                any(),
            )

        assertThat(response.memberId).isEqualTo(memberId)
        assertThat(response.balance).isEqualTo(1050000)
        assertThat(response.lastStatusChangedAt).isNull()
        assertThat(response.lastStatusChangeReason).isNull()
    }

    @Test
    @DisplayName("관리자는 자기 자신에게 크레딧을 지급할 수 없다")
    fun grantCredit_selfGrant_throwsException() {
        // given
        val adminMemberId: Long = 100L

        // when
        val exception: BusinessException =
            assertThrows(
                BusinessException::class.java,
                {
                    adminMemberService.grantCredit(
                        adminMemberId,
                        50000,
                        "자기 자신 지급",
                        adminMemberId,
                    )
                },
            )

        // then
        assertThat(exception.errorCode)
            .isEqualTo(
                ErrorCode.SELF_GRANT_NOT_ALLOWED,
            )

        verifyNoInteractions(
            memberRepository,
            creditGrantService,
            auditLogService,
        )
        verifyNoInteractions(notificationEventRecorder)
    }

    @Test
    @DisplayName("존재하지 않는 회원에게 크레딧을 지급하면 MEMBER_NOT_FOUND 예외가 발생한다")
    fun grantCredit_memberNotFound_throwsException() {
        // given
        val memberId: Long = 999L

        whenever(memberRepository.findByIdForUpdate(memberId)).thenReturn(Optional.empty())

        // when
        val exception: BusinessException =
            assertThrows(
                BusinessException::class.java,
                {
                    adminMemberService.grantCredit(
                        memberId,
                        50000,
                        "지급",
                        100L,
                    )
                },
            )

        // then
        assertThat(exception.errorCode).isEqualTo(ErrorCode.MEMBER_NOT_FOUND)

        verifyNoInteractions(
            creditGrantService,
            auditLogService,
        )
    }
}
