@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.entity.LocalCredential
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.mail.VerificationMailSender
import com.ovengers.slotkey.auth.repository.AuthSessionRepository
import com.ovengers.slotkey.auth.repository.LocalCredentialRepository
import com.ovengers.slotkey.auth.repository.VerificationGrantRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.service.MemberService
import com.ovengers.slotkey.support.MySqlTestContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainerConfig::class)
internal class PasswordResetIntegrationTest {
    @Autowired
    private lateinit var passwordResetService: PasswordResetService

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var memberService: MemberService

    @Autowired
    private lateinit var challengeService: VerificationChallengeService

    @Autowired
    private lateinit var checkService: VerificationCheckService

    @Autowired
    private lateinit var localCredentialRepository: LocalCredentialRepository

    @Autowired
    private lateinit var grantRepository: VerificationGrantRepository

    @SpyBean
    private lateinit var authSessionRepository: AuthSessionRepository

    @Autowired
    private lateinit var secretHasher: VerificationSecretHasher

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @MockBean
    private lateinit var mailSender: VerificationMailSender

    @Test fun 재설정_증표로_로컬_비밀번호를_변경하고_증표를_소비한다() {
        val email: String = newEmail()
        val member: Member = createMember(email, "Old1234!")
        val grant: String = issueGrant(email, VerificationPurpose.PASSWORD_RESET)

        passwordResetService.reset(
            email,
            grant,
            "New1234!",
            "New1234!",
        )

        val credential: LocalCredential =
            localCredentialRepository
                .findById(member.id!!)
                .orElseThrow()
        assertThat(passwordEncoder.matches("Old1234!", credential.passwordHash))
            .isFalse()
        assertThat(passwordEncoder.matches("New1234!", credential.passwordHash))
            .isTrue()
        assertThat(
            grantRepository
                .findByGrantHash(secretHasher.hashGrant(grant))
                .orElseThrow()
                .consumedAt,
        ).isNotNull()
    }

    @Test fun 비밀번호_확인_실패_후_같은_재설정_증표로_재시도할_수_있다() {
        val email: String = newEmail()
        val member: Member = createMember(email, "Old1234!")
        val grant: String = issueGrant(email, VerificationPurpose.PASSWORD_RESET)

        assertThatThrownBy {
            passwordResetService.reset(
                email,
                grant,
                "New1234!",
                "Different1234!",
            )
        }.isInstanceOf(BusinessException::class.java)

        assertThat(
            grantRepository
                .findByGrantHash(secretHasher.hashGrant(grant))
                .orElseThrow()
                .consumedAt,
        ).isNull()
        val originalHash: String =
            localCredentialRepository
                .findById(member.id!!)
                .orElseThrow()
                .passwordHash

        passwordResetService.reset(
            email,
            grant,
            "New1234!",
            "New1234!",
        )

        assertThat(
            localCredentialRepository
                .findById(member.id!!)
                .orElseThrow()
                .passwordHash,
        ).isNotEqualTo(originalHash)
    }

    @Test fun 가입용_증표로는_비밀번호를_재설정할_수_없다() {
        val email: String = newEmail()
        createMember(email, "Old1234!")
        val signupGrant: String = issueGrant(newEmail(), VerificationPurpose.SIGNUP)

        assertThatThrownBy {
            passwordResetService.reset(
                email,
                signupGrant,
                "New1234!",
                "New1234!",
            )
        }.isInstanceOf(BusinessException::class.java)
    }

    @Test fun 비밀번호_재설정_후_새_비밀번호로_로그인할_수_있다() {
        val email: String = newEmail()
        createMember(email, "Old1234!")
        val grant: String = issueGrant(email, VerificationPurpose.PASSWORD_RESET)

        passwordResetService.reset(
            email,
            grant,
            "New1234!",
            "New1234!",
        )

        assertThat(authService.login(email, "New1234!").accessToken)
            .isNotBlank()
    }

    @Test fun 세션_폐기_실패시_비밀번호와_증표_소비도_함께_롤백된다() {
        val email: String = newEmail()
        val member: Member = createMember(email, "Old1234!")
        val oldRefreshToken: String = authService.login(email, "Old1234!").refreshToken
        val grant: String = issueGrant(email, VerificationPurpose.PASSWORD_RESET)

        doThrow(IllegalStateException("session revoke failed"))
            .whenever(authSessionRepository)
            .revokeAllByMemberId(
                eq(member.id!!),
                any<LocalDateTime>(),
            )

        assertThatThrownBy {
            passwordResetService.reset(
                email,
                grant,
                "New1234!",
                "New1234!",
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("session revoke failed")

        assertThat(
            grantRepository
                .findByGrantHash(secretHasher.hashGrant(grant))
                .orElseThrow()
                .consumedAt,
        ).isNull()
        assertThat(
            passwordEncoder.matches(
                "Old1234!",
                localCredentialRepository.findById(member.id!!).orElseThrow().passwordHash,
            ),
        ).isTrue()
        assertThat(authService.refresh(oldRefreshToken).accessToken).isNotBlank()
    }

    private fun createMember(
        email: String,
        rawPassword: String,
    ): Member {
        val signupGrant: String = issueGrant(email, VerificationPurpose.SIGNUP)
        return memberService.signup(
            email,
            rawPassword,
            rawPassword,
            "테스트회원",
            signupGrant,
        )
    }

    private fun issueGrant(
        email: String,
        purpose: VerificationPurpose,
    ): String {
        val deliveredCode: AtomicReference<String> = AtomicReference()

        doAnswer { invocation ->
            deliveredCode.set(invocation.getArgument(2, String::class.java))
            null
        }.whenever(mailSender).sendCode(
            eq(email),
            eq(purpose),
            any<String>(),
        )

        val issued = challengeService.issue(email, purpose)
        val checked =
            checkService.verify(
                email,
                purpose,
                issued.challengeId,
                deliveredCode.get()!!,
            )
        return checked.grant!!
    }

    private fun newEmail(): String = "reset-${UUID.randomUUID()}@example.com"
}
