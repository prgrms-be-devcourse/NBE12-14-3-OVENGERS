@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.mail.VerificationMailSender
import com.ovengers.slotkey.auth.repository.VerificationGrantRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.member.service.MemberService
import com.ovengers.slotkey.support.MySqlTestContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainerConfig::class)
internal class EmailSignupIntegrationTest {
    @Autowired
    private lateinit var memberService: MemberService

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var challengeService: VerificationChallengeService

    @Autowired
    private lateinit var checkService: VerificationCheckService

    @Autowired
    private lateinit var grantRepository: VerificationGrantRepository

    @Autowired
    private lateinit var secretHasher: VerificationSecretHasher

    @MockBean
    private lateinit var mailSender: VerificationMailSender

    @Test fun 비밀번호_확인에_실패해도_같은_증표로_다시_가입할_수_있다() {
        val email: String = newEmail()
        val grant: String = issueSignupGrant(email)

        assertThatThrownBy {
            memberService.signup(
                email,
                "Test1234!",
                "Different1234!",
                "테스트회원",
                grant,
            )
        }.isInstanceOf(BusinessException::class.java)

        assertThat(memberRepository.existsByEmail(email)).isFalse()
        assertNotConsumed(grant)

        val member =
            memberService.signup(
                email,
                "Test1234!",
                "Test1234!",
                "테스트회원",
                grant,
            )

        assertThat(member.emailVerifiedAt).isNotNull()

        val storedGrant =
            grantRepository
                .findByGrantHash(secretHasher.hashGrant(grant))
                .orElseThrow()

        assertThat(storedGrant.consumedAt).isNotNull()
    }

    @Test fun 다른_이메일로_가입할_수_없고_원래_이메일의_증표는_유지된다() {
        val email: String = newEmail()
        val otherEmail: String = newEmail()
        val grant: String = issueSignupGrant(email)

        assertThatThrownBy {
            memberService.signup(
                otherEmail,
                "Test1234!",
                "Test1234!",
                "테스트회원",
                grant,
            )
        }.isInstanceOf(BusinessException::class.java)

        assertThat(memberRepository.existsByEmail(otherEmail)).isFalse()
        assertNotConsumed(grant)

        memberService.signup(
            email,
            "Test1234!",
            "Test1234!",
            "테스트회원",
            grant,
        )

        assertThat(memberRepository.existsByEmail(email)).isTrue()
    }

    private fun assertNotConsumed(rawGrant: String) {
        val storedGrant =
            grantRepository
                .findByGrantHash(secretHasher.hashGrant(rawGrant))
                .orElseThrow()

        assertThat(storedGrant.consumedAt).isNull()
        assertThat(storedGrant.invalidatedAt).isNull()
    }

    private fun issueSignupGrant(email: String): String {
        val deliveredCode: AtomicReference<String> = AtomicReference()

        doAnswer { invocation ->
            deliveredCode.set(invocation.getArgument(2, String::class.java))
            null
        }.whenever(mailSender).sendCode(
            eq(email),
            eq(VerificationPurpose.SIGNUP),
            any<String>(),
        )

        val issued =
            challengeService.issue(
                email,
                VerificationPurpose.SIGNUP,
            )

        val checked =
            checkService.verify(
                email,
                VerificationPurpose.SIGNUP,
                issued.challengeId,
                deliveredCode.get()!!,
            )

        assertThat(checked.grant).isNotBlank()
        return checked.grant!!
    }

    private fun newEmail(): String = "signup-${UUID.randomUUID()}@example.com"
}
