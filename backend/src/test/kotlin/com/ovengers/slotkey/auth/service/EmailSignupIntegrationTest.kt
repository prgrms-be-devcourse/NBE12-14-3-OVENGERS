@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.mail.VerificationMailSender
import com.ovengers.slotkey.auth.repository.LocalCredentialRepository
import com.ovengers.slotkey.auth.repository.VerificationGrantRepository
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.member.repository.MemberRepository
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
import org.springframework.data.domain.PageRequest
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

    // 추가: 회원가입 크레딧 저장 및 롤백 검증
    @Autowired
    private lateinit var localCredentialRepository: LocalCredentialRepository

    @SpyBean
    private lateinit var creditTransactionRepository: CreditTransactionRepository

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

    // 추가 테스트 1: 회원가입 크레딧 지급 성공
    @Test
    fun 회원가입_성공시_크레딧_잔액과_지급_원장이_DB에_저장된다() {
        // given
        val email = newEmail()
        val grant = issueSignupGrant(email)

        // when
        val member =
            memberService.signup(
                email,
                "Test1234!",
                "Test1234!",
                "테스트회원",
                grant,
            )

        val memberId = requireNotNull(member.id)

        // then: 회원과 크레딧 잔액 확인
        val savedMember = memberRepository.findById(memberId).orElseThrow()

        assertThat(savedMember.email).isEqualTo(email)
        assertThat(savedMember.balance).isEqualTo(SIGNUP_GRANT_AMOUNT)
        assertThat(savedMember.emailVerifiedAt).isNotNull()

        // then: 크레딧 지급 원장 확인
        val transactions =
            creditTransactionRepository
                .findByMember_Id(memberId, PageRequest.of(0, 10))
                .content

        assertThat(transactions).hasSize(1)

        val transaction = transactions.single()

        assertThat(transaction.member.id).isEqualTo(memberId)
        assertThat(transaction.type).isEqualTo(CreditTransactionType.SIGNUP_GRANT)
        assertThat(transaction.amount).isEqualTo(SIGNUP_GRANT_AMOUNT)
        assertThat(transaction.balanceAfter).isEqualTo(SIGNUP_GRANT_AMOUNT)
        assertThat(transaction.reservation).isNull()
        assertThat(transaction.reason).isNull()

        // then: 로그인 자격 증명과 인증 증표 소비 확인
        assertThat(localCredentialRepository.findById(memberId)).isPresent()

        val storedGrant =
            grantRepository
                .findByGrantHash(secretHasher.hashGrant(grant))
                .orElseThrow()

        assertThat(storedGrant.consumedAt).isNotNull()
    }

    // 추가 테스트 2: 크레딧 원장 저장 실패 시 회원가입 롤백
    @Test
    fun 회원가입_크레딧_원장_저장_실패시_전체_트랜잭션이_롤백된다() {
        // given
        val email = newEmail()
        val grant = issueSignupGrant(email)

        val initialTransactionCount = creditTransactionRepository.count()
        val initialCredentialCount = localCredentialRepository.count()

        // 크레딧 원장 저장 시 예외 발생
        doThrow(RuntimeException("SIGNUP_GRANT 저장 실패"))
            .whenever(creditTransactionRepository)
            .save(any())

        // when
        assertThatThrownBy {
            memberService.signup(
                email,
                "Test1234!",
                "Test1234!",
                "테스트회원",
                grant,
            )
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessage("SIGNUP_GRANT 저장 실패")

        // then: 회원 생성 롤백
        assertThat(memberRepository.existsByEmail(email)).isFalse()

        // then: 로그인 자격 증명과 크레딧 원장 롤백
        assertThat(localCredentialRepository.count()).isEqualTo(initialCredentialCount)
        assertThat(creditTransactionRepository.count()).isEqualTo(initialTransactionCount)

        // then: 인증 증표 소비 롤백
        assertNotConsumed(grant)
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

    private companion object {
        const val SIGNUP_GRANT_AMOUNT = 300_000
    }
}
