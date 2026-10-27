@file:Suppress("ktlint:standard:function-naming")

package com.ovengers.slotkey.auth.service

import com.ovengers.slotkey.auth.dto.internal.ChallengeIssueResult
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.ADMIN_LOCKED
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.INVALID_CHALLENGE
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.SUCCESS
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.TEMPORARILY_BLOCKED
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult.Status.WRONG_CODE
import com.ovengers.slotkey.auth.entity.VerificationPurpose
import com.ovengers.slotkey.auth.mail.VerificationMailSender
import com.ovengers.slotkey.auth.repository.VerificationGrantRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.support.MySqlTestContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.mail.MailSendException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainerConfig::class)
internal class VerificationFlowIntegrationTest {
    @Autowired
    private lateinit var challengeService: VerificationChallengeService

    @Autowired
    private lateinit var checkService: VerificationCheckService

    @Autowired
    private lateinit var grantRepository: VerificationGrantRepository

    @MockBean
    private lateinit var mailSender: VerificationMailSender

    @MockBean
    private lateinit var clock: Clock

    @Autowired
    private lateinit var guardService: VerificationGuardService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager
    private val currentTime: AtomicReference<Instant> = AtomicReference()

    private val deliveredCode: AtomicReference<String> = AtomicReference()

    private lateinit var email: String

    @BeforeEach fun setUp() {
        email = "verification-${UUID.randomUUID()}@example.com"

        currentTime.set(Instant.parse("2026-10-13T00:00:00Z"))

        whenever(clock.zone).thenReturn(ZoneId.of("Asia/Seoul"))
        whenever(clock.instant()).thenAnswer { currentTime.get() }

        doAnswer { invocation ->
            deliveredCode.set(invocation.getArgument(2, String::class.java))
            null
        }.whenever(mailSender).sendCode(
            eq(email),
            eq(VerificationPurpose.SIGNUP),
            any<String>(),
        )
    }

    @Test fun 인증_성공_후_같은_번호로_증표를_다시_발급하지_않는다() {
        val issued: ChallengeIssueResult = issue()
        val code: String = deliveredCode.get()!!

        val first: VerificationCheckResult = verify(issued, code)

        assertThat(first.status).isEqualTo(SUCCESS)
        assertThat(first.grant).isNotBlank()

        val savedGrant =
            grantRepository
                .findByGrantHash(
                    secretHasher.hashGrant(first.grant!!),
                ).orElseThrow()

        assertThat(savedGrant.expiresAt)
            .isEqualTo(savedGrant.issuedAt.plusMinutes(5))

        val repeated: VerificationCheckResult = verify(issued, code)

        assertThat(repeated.status).isEqualTo(INVALID_CHALLENGE)
        assertThat(repeated.grant).isNull()
    }

    @Test fun 네_번_실패한_뒤_재발송해도_다섯번째_실패에서_차단된다() {
        val first: ChallengeIssueResult = issue()
        val firstWrongCode: String = wrongCode(deliveredCode.get()!!)

        for (i in 0..3) {
            assertThat(verify(first, firstWrongCode).status)
                .isEqualTo(WRONG_CODE)
        }

        currentTime.updateAndGet { time -> time.plusSeconds(30) }

        val second: ChallengeIssueResult = issue()

        // 이전 번호는 정확히 입력해도 사용할 수 없다.
        assertThat(verify(first, firstWrongCode).status)
            .isEqualTo(INVALID_CHALLENGE)

        val fifth: VerificationCheckResult =
            verify(
                second,
                wrongCode(deliveredCode.get()!!),
            )

        assertThat(fifth.status).isEqualTo(TEMPORARILY_BLOCKED)
        assertThat(fifth.blockedUntil)
            .isEqualTo(
                second.resendAvailableAt
                    .plusMinutes(5)
                    .minusSeconds(30),
            )

        // 차단 중에는 올바른 번호도 거부한다.
        assertThat(verify(second, deliveredCode.get()!!).status)
            .isEqualTo(TEMPORARILY_BLOCKED)
    }

    @Autowired
    private lateinit var secretHasher: VerificationSecretHasher

    private fun issue(): ChallengeIssueResult =
        challengeService.issue(
            email,
            VerificationPurpose.SIGNUP,
        )

    private fun verify(
        issued: ChallengeIssueResult,
        code: String,
    ): VerificationCheckResult =
        checkService.verify(
            email,
            VerificationPurpose.SIGNUP,
            issued.challengeId,
            code,
        )

    private fun wrongCode(actual: String): String =
        if (actual.equals("00000000")) {
            "00000001"
        } else {
            "00000000"
        }

    @Test fun 반복_실패하면_5분_1시간_관리자_차단으로_진행한다() {
        val first: ChallengeIssueResult = issue()

        val firstBlock: VerificationCheckResult = failFiveTimes(first)

        assertThat(firstBlock.status).isEqualTo(TEMPORARILY_BLOCKED)
        assertThat(firstBlock.blockedUntil).isEqualTo(now().plusMinutes(5))

        // 정확히 5분 후에는 새 번호 발급 가능
        currentTime.updateAndGet { time -> time.plusSeconds(5 * 60) }

        val second: ChallengeIssueResult = issue()
        val secondBlock: VerificationCheckResult = failFiveTimes(second)

        assertThat(secondBlock.status).isEqualTo(TEMPORARILY_BLOCKED)
        assertThat(secondBlock.blockedUntil).isEqualTo(now().plusHours(1))

        currentTime.updateAndGet { time -> time.plusSeconds(60 * 60) }

        val third: ChallengeIssueResult = issue()
        val thirdBlock: VerificationCheckResult = failFiveTimes(third)

        assertThat(thirdBlock.status).isEqualTo(ADMIN_LOCKED)
        assertThat(thirdBlock.blockedUntil).isNull()

        // 시간이 지나도 관리자 차단은 유지
        currentTime.updateAndGet { time -> time.plusSeconds(24 * 60 * 60) }

        assertThat(verify(third, deliveredCode.get()!!).status)
            .isEqualTo(ADMIN_LOCKED)

        assertThatThrownBy { issue() }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.VERIFICATION_ADMIN_LOCKED,
            )
    }

    private fun failFiveTimes(issued: ChallengeIssueResult): VerificationCheckResult {
        val wrong: String = wrongCode(deliveredCode.get()!!)

        for (i in 0..3) {
            assertThat(verify(issued, wrong).status)
                .isEqualTo(WRONG_CODE)
        }

        return verify(issued, wrong)
    }

    private fun now(): LocalDateTime =
        LocalDateTime.ofInstant(
            currentTime.get(),
            clock.zone,
        )

    @Test fun 재발송_실패하면_기존_번호를_계속_사용할_수_있다() {
        val first: ChallengeIssueResult = issue()
        val originalCode: String = deliveredCode.get()!!

        currentTime.updateAndGet { time -> time.plusSeconds(30) }

        doThrow(MailSendException("테스트용 발송 실패"))
            .whenever(mailSender)
            .sendCode(
                eq(email),
                eq(VerificationPurpose.SIGNUP),
                any<String>(),
            )

        assertThatThrownBy { issue() }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.VERIFICATION_MAIL_FAILED,
            )

        // 재발송 실패로 이전 번호 무효화가 롤백돼야 한다.
        assertThat(verify(first, originalCode).status)
            .isEqualTo(SUCCESS)
    }

    @Test fun 인증에_성공하면_다음_차단은_다시_5분부터_시작한다() {
        failFiveTimes(issue())

        currentTime.updateAndGet { time -> time.plusSeconds(5 * 60) }

        val second: ChallengeIssueResult = issue()

        assertThat(verify(second, deliveredCode.get()!!).status)
            .isEqualTo(SUCCESS)

        currentTime.updateAndGet { time -> time.plusSeconds(30) }

        val blockedAgain: VerificationCheckResult = failFiveTimes(issue())

        assertThat(blockedAgain.status).isEqualTo(TEMPORARILY_BLOCKED)
        assertThat(blockedAgain.blockedUntil)
            .isEqualTo(now().plusMinutes(5))
    }

    @Test fun 관리자_해제_후에는_인증을_재개하고_5분부터_차단한다() {
        failFiveTimes(issue())

        currentTime.updateAndGet { time -> time.plusSeconds(5 * 60) }
        failFiveTimes(issue())

        currentTime.updateAndGet { time -> time.plusSeconds(60 * 60) }
        assertThat(failFiveTimes(issue()).status)
            .isEqualTo(ADMIN_LOCKED)

        TransactionTemplate(transactionManager)
            .executeWithoutResult {
                val guard =
                    guardService.acquire(
                        email,
                        VerificationPurpose.SIGNUP,
                    )

                guard.unlockByAdmin()
            }

        // 관리자 해제와 별개로 마지막 발송의 30초 간격은 유지
        currentTime.updateAndGet { time -> time.plusSeconds(30) }

        val blockedAgain: VerificationCheckResult = failFiveTimes(issue())

        assertThat(blockedAgain.status).isEqualTo(TEMPORARILY_BLOCKED)
        assertThat(blockedAgain.blockedUntil)
            .isEqualTo(now().plusMinutes(5))
    }

    @Test
    fun 같은_번호를_동시에_확인해도_한_요청만_성공한다() {
        val issued: ChallengeIssueResult = issue()
        val code: String = deliveredCode.get()!!

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        val executor: ExecutorService = Executors.newFixedThreadPool(2)

        val request: Callable<VerificationCheckResult> =
            Callable<VerificationCheckResult> {
                ready.countDown()

                check(start.await(10, TimeUnit.SECONDS)) { "시작 신호 대기 시간 초과" }

                verify(issued, code)
            }

        try {
            val first: Future<VerificationCheckResult> =
                executor.submit(request)

            val second: Future<VerificationCheckResult> =
                executor.submit(request)

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            val firstResult: VerificationCheckResult =
                first.get(15, TimeUnit.SECONDS)

            val secondResult: VerificationCheckResult =
                second.get(15, TimeUnit.SECONDS)

            assertThat(
                listOf(
                    firstResult.status,
                    secondResult.status,
                ),
            ).containsExactlyInAnyOrder(
                SUCCESS,
                INVALID_CHALLENGE,
            )

            val success: VerificationCheckResult =
                if (firstResult.status == SUCCESS) {
                    firstResult
                } else {
                    secondResult
                }

            assertThat(
                grantRepository.findByGrantHash(
                    secretHasher.hashGrant(success.grant!!),
                ),
            ).isPresent()
        } finally {
            start.countDown()
            executor.shutdownNow()

            assertThat(
                executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS,
                ),
            ).isTrue()
        }
    }

    private fun <T> runConcurrently(
        requestCount: Int,
        request: Callable<T>,
    ): List<T> {
        val ready = CountDownLatch(requestCount)
        val start = CountDownLatch(1)

        val executor: ExecutorService =
            Executors.newFixedThreadPool(requestCount)

        try {
            val futures = mutableListOf<Future<T>>()

            repeat(requestCount) {
                futures.add(
                    executor.submit(
                        Callable {
                            ready.countDown()

                            check(start.await(10, TimeUnit.SECONDS)) { "시작 대기 시간 초과" }

                            request.call()
                        },
                    ),
                )
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            return futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()

            assertThat(
                executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS,
                ),
            ).isTrue()
        }
    }

    @Test
    fun 동시에_다섯번_틀려도_횟수가_누락되지_않는다() {
        val issued: ChallengeIssueResult = issue()
        val wrong: String = wrongCode(deliveredCode.get()!!)

        val results: List<VerificationCheckResult> =
            runConcurrently<VerificationCheckResult>(
                5,
                Callable { verify(issued, wrong) },
            )

        assertThat(results.count { it.status == WRONG_CODE }).isEqualTo(4)

        assertThat(results.count { it.status == TEMPORARILY_BLOCKED }).isEqualTo(1)

        assertThat(verify(issued, deliveredCode.get()!!).status)
            .isEqualTo(TEMPORARILY_BLOCKED)
    }

    @Test
    fun 최초_발급이_동시에_들어와도_한번만_발송한다() {
        val results: List<Boolean> =
            runConcurrently<Boolean>(
                2,
                Callable {
                    try {
                        issue()
                        return@Callable true
                    } catch (e: BusinessException) {
                        if (e.errorCode
                            != ErrorCode.VERIFICATION_RESEND_TOO_SOON
                        ) {
                            throw e
                        }

                        return@Callable false
                    }
                },
            )

        assertThat(results).containsExactlyInAnyOrder(true, false)

        verify(mailSender, times(1)).sendCode(
            eq(email),
            eq(VerificationPurpose.SIGNUP),
            any<String>(),
        )
    }
}
