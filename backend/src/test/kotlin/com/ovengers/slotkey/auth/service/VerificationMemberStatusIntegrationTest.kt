@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.audit.entity.AuditTargetType
import com.ovengers.slotkey.audit.repository.AuditLogRepository
import com.ovengers.slotkey.auth.dto.internal.ChallengeIssueResult
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.ADMIN_LOCKED
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.TEMPORARILY_BLOCKED
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.mail.VerificationMailSender
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.member.service.AdminMemberService
import com.ovengers.slotkey.member.service.MemberService
import com.ovengers.slotkey.support.MySqlTestContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainerConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class VerificationMemberStatusIntegrationTest {
    @Autowired
    private lateinit var challengeService: VerificationChallengeService

    @Autowired
    private lateinit var checkService: VerificationCheckService

    @Autowired
    private lateinit var memberService: MemberService

    @Autowired
    private lateinit var adminMemberService: AdminMemberService

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @MockBean
    private lateinit var mailSender: VerificationMailSender

    @MockBean
    private lateinit var clock: Clock

    private val currentTime: AtomicReference<Instant> = AtomicReference()

    @BeforeEach fun setUp() {
        currentTime.set(Instant.parse("2026-10-14T00:00:00Z"))
        whenever(clock.zone).thenReturn(ZoneId.of("Asia/Seoul"))
        whenever(clock.instant()).thenAnswer { currentTime.get() }
    }

    @Test fun 세번째_인증차단이면_기존_ACTIVE_회원을_정지하고_시스템_감사로그를_남긴다() {
        val email: String = newEmail()
        val member: Member = createMember(email)

        val firstBlock: VerificationCheckResult = failFiveTimes(email)
        assertThat(firstBlock.status).isEqualTo(TEMPORARILY_BLOCKED)
        assertMemberStatus(member.id!!, MemberStatus.ACTIVE)
        assertVerificationLockAuditCount(member.id!!, 0)

        currentTime.updateAndGet { time -> time.plusSeconds(5 * 60L + 30) }
        val secondBlock: VerificationCheckResult = failFiveTimes(email)
        assertThat(secondBlock.status).isEqualTo(TEMPORARILY_BLOCKED)
        assertMemberStatus(member.id!!, MemberStatus.ACTIVE)
        assertVerificationLockAuditCount(member.id!!, 0)

        currentTime.updateAndGet { time -> time.plusSeconds(60 * 60L + 30) }
        val thirdBlock: VerificationCheckResult = failFiveTimes(email)
        assertThat(thirdBlock.status).isEqualTo(ADMIN_LOCKED)

        assertMemberStatus(member.id!!, MemberStatus.SUSPENDED)
        assertVerificationLockAuditCount(member.id!!, 1)

        val logs =
            auditLogRepository.searchAuditLogs(
                null,
                AuditAction.LOCK_MEMBER_BY_VERIFICATION,
                AuditTargetType.MEMBER,
                member.id!!,
                null,
                null,
                PageRequest.of(0, 10),
            )

        assertThat(logs.totalElements).isEqualTo(1)
        val log = logs.content[0]
        assertThat(log.actorMemberId).isNull()
        assertThat(log.beforeValue).isEqualTo("\"ACTIVE\"")
        assertThat(log.afterValue).isEqualTo("\"SUSPENDED\"")
    }

    private fun assertMemberStatus(
        memberId: Long,
        expectedStatus: MemberStatus,
    ) {
        assertThat(memberRepository.findById(memberId).orElseThrow().status)
            .isEqualTo(expectedStatus)
    }

    private fun assertVerificationLockAuditCount(
        memberId: Long,
        expectedCount: Long,
    ) {
        val logs =
            auditLogRepository.searchAuditLogs(
                null,
                AuditAction.LOCK_MEMBER_BY_VERIFICATION,
                AuditTargetType.MEMBER,
                memberId,
                null,
                null,
                PageRequest.of(0, 10),
            )

        assertThat(logs.totalElements).isEqualTo(expectedCount)
    }

    @Test fun 관리자가_회원복구하면_해당_이메일의_인증잠금도_해제된다() {
        val email: String = newEmail()
        val member: Member = createMember(email)

        failFiveTimes(email)
        currentTime.updateAndGet { time -> time.plusSeconds(5 * 60L + 30) }
        failFiveTimes(email)
        currentTime.updateAndGet { time -> time.plusSeconds(60 * 60L + 30) }
        assertThat(failFiveTimes(email).status).isEqualTo(ADMIN_LOCKED)

        adminMemberService.restore(member.id!!, "인증 잠금 해제", 999L)

        assertThat(memberRepository.findById(member.id!!).orElseThrow().status)
            .isEqualTo(MemberStatus.ACTIVE)

        currentTime.updateAndGet { time -> time.plusSeconds(30) }
        val deliveredCode: AtomicReference<String> = AtomicReference()
        doAnswer { invocation ->
            deliveredCode.set(invocation.getArgument(2, String::class.java))
            null
        }.whenever(mailSender).sendCode(
            eq(email),
            eq(VerificationPurpose.PASSWORD_RESET),
            any<String>(),
        )

        val issued: ChallengeIssueResult =
            challengeService.issue(
                email,
                VerificationPurpose.PASSWORD_RESET,
            )
        assertThat(deliveredCode).isNotNull()
        assertThat(issued.challengeId).isNotNull()
    }

    private fun createMember(email: String): Member {
        val signupGrant: String = issueGrant(email, VerificationPurpose.SIGNUP)
        return memberService.signup(
            email,
            "Test1234!",
            "Test1234!",
            "테스트회원",
            signupGrant,
        )
    }

    private fun failFiveTimes(email: String): VerificationCheckResult {
        val deliveredCode: AtomicReference<String> = AtomicReference()
        doAnswer { invocation ->
            deliveredCode.set(invocation.getArgument(2, String::class.java))
            null
        }.whenever(mailSender).sendCode(
            eq(email),
            eq(VerificationPurpose.PASSWORD_RESET),
            any<String>(),
        )

        val issued: ChallengeIssueResult =
            challengeService.issue(
                email,
                VerificationPurpose.PASSWORD_RESET,
            )
        val wrongCode: String =
            if (deliveredCode.get()!!.equals("00000000")) {
                "00000001"
            } else {
                "00000000"
            }
        lateinit var result: VerificationCheckResult
        for (i in 0..4) {
            result =
                checkService.verify(
                    email,
                    VerificationPurpose.PASSWORD_RESET,
                    issued.challengeId,
                    wrongCode,
                )
        }
        return result
    }

    private fun issueGrant(
        email: String,
        purpose: VerificationPurpose,
    ): String {
        val deliveredCode: AtomicReference<String> = AtomicReference()
        doAnswer { invocation ->
            deliveredCode.set(invocation.getArgument(2, String::class.java))
            null
        }.whenever(mailSender).sendCode(eq(email), eq(purpose), any<String>())
        val issued = challengeService.issue(email, purpose)
        return checkService
            .verify(
                email,
                purpose,
                issued.challengeId,
                deliveredCode.get()!!,
            ).grant!!
    }

    private fun newEmail(): String = "status-link-${UUID.randomUUID()}@example.com"
}
