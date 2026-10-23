package com.ovengers.slotkey.auth.service;

import com.ovengers.slotkey.auth.dto.internal.ChallengeIssueResult;
import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult;
import com.ovengers.slotkey.auth.entity.VerificationPurpose;
import com.ovengers.slotkey.auth.mail.VerificationMailSender;
import com.ovengers.slotkey.auth.repository.VerificationGrantRepository;
import com.ovengers.slotkey.support.MySqlTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ovengers.slotkey.auth.dto.internal
        .VerificationCheckResult.Status.*;
import org.springframework.mail.MailSendException;
import com.ovengers.slotkey.global.error.BusinessException;
import com.ovengers.slotkey.global.error.ErrorCode;
import java.time.LocalDateTime;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.concurrent.*;
import java.util.ArrayList;
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainerConfig.class)
class VerificationFlowIntegrationTest {

    @Autowired
    private VerificationChallengeService challengeService;

    @Autowired
    private VerificationCheckService checkService;

    @Autowired
    private VerificationGrantRepository grantRepository;

    @MockBean
    private VerificationMailSender mailSender;

    @MockBean
    private Clock clock;
    @Autowired
    private VerificationGuardService guardService;

    @Autowired
    private PlatformTransactionManager transactionManager;
    private final AtomicReference<Instant> currentTime =
            new AtomicReference<>();

    private final AtomicReference<String> deliveredCode =
            new AtomicReference<>();

    private String email;

    @BeforeEach
    void setUp() {
        email = "verification-" + UUID.randomUUID() + "@example.com";

        currentTime.set(Instant.parse("2026-10-13T00:00:00Z"));

        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenAnswer(
                invocation -> currentTime.get()
        );

        doAnswer(invocation -> {
            deliveredCode.set(invocation.getArgument(2, String.class));
            return null;
        }).when(mailSender).sendCode(
                eq(email),
                eq(VerificationPurpose.SIGNUP),
                anyString()
        );
    }

    @Test
    void 인증_성공_후_같은_번호로_증표를_다시_발급하지_않는다() {
        ChallengeIssueResult issued = issue();
        String code = deliveredCode.get();

        VerificationCheckResult first = verify(issued, code);

        assertThat(first.status()).isEqualTo(SUCCESS);
        assertThat(first.grant()).isNotBlank();

        var savedGrant = grantRepository.findByGrantHash(
                secretHasher.hashGrant(first.grant())
        ).orElseThrow();

        assertThat(savedGrant.getExpiresAt())
                .isEqualTo(savedGrant.getIssuedAt().plusMinutes(5));

        VerificationCheckResult repeated = verify(issued, code);

        assertThat(repeated.status()).isEqualTo(INVALID_CHALLENGE);
        assertThat(repeated.grant()).isNull();
    }

    @Test
    void 네_번_실패한_뒤_재발송해도_다섯번째_실패에서_차단된다() {
        ChallengeIssueResult first = issue();
        String firstWrongCode = wrongCode(deliveredCode.get());

        for (int i = 0; i < 4; i++) {
            assertThat(verify(first, firstWrongCode).status())
                    .isEqualTo(WRONG_CODE);
        }

        currentTime.updateAndGet(time -> time.plusSeconds(30));

        ChallengeIssueResult second = issue();

        // 이전 번호는 정확히 입력해도 사용할 수 없다.
        assertThat(verify(first, firstWrongCode).status())
                .isEqualTo(INVALID_CHALLENGE);

        VerificationCheckResult fifth = verify(
                second,
                wrongCode(deliveredCode.get())
        );

        assertThat(fifth.status()).isEqualTo(TEMPORARILY_BLOCKED);
        assertThat(fifth.blockedUntil())
                .isEqualTo(second.resendAvailableAt().plusMinutes(5)
                        .minusSeconds(30));

        // 차단 중에는 올바른 번호도 거부한다.
        assertThat(verify(second, deliveredCode.get()).status())
                .isEqualTo(TEMPORARILY_BLOCKED);
    }

    @Autowired
    private VerificationSecretHasher secretHasher;

    private ChallengeIssueResult issue() {
        return challengeService.issue(
                email,
                VerificationPurpose.SIGNUP
        );
    }

    private VerificationCheckResult verify(
            ChallengeIssueResult issued,
            String code
    ) {
        return checkService.verify(
                email,
                VerificationPurpose.SIGNUP,
                issued.challengeId(),
                code
        );
    }

    private String wrongCode(String actual) {
        return actual.equals("00000000")
                ? "00000001"
                : "00000000";
    }

    @Test
    void 반복_실패하면_5분_1시간_관리자_차단으로_진행한다() {
        ChallengeIssueResult first = issue();

        VerificationCheckResult firstBlock = failFiveTimes(first);

        assertThat(firstBlock.status()).isEqualTo(TEMPORARILY_BLOCKED);
        assertThat(firstBlock.blockedUntil()).isEqualTo(now().plusMinutes(5));

        // 정확히 5분 후에는 새 번호 발급 가능
        currentTime.updateAndGet(time -> time.plusSeconds(5 * 60));

        ChallengeIssueResult second = issue();
        VerificationCheckResult secondBlock = failFiveTimes(second);

        assertThat(secondBlock.status()).isEqualTo(TEMPORARILY_BLOCKED);
        assertThat(secondBlock.blockedUntil()).isEqualTo(now().plusHours(1));

        currentTime.updateAndGet(time -> time.plusSeconds(60 * 60));

        ChallengeIssueResult third = issue();
        VerificationCheckResult thirdBlock = failFiveTimes(third);

        assertThat(thirdBlock.status()).isEqualTo(ADMIN_LOCKED);
        assertThat(thirdBlock.blockedUntil()).isNull();

        // 시간이 지나도 관리자 차단은 유지
        currentTime.updateAndGet(time -> time.plusSeconds(24 * 60 * 60));

        assertThat(verify(third, deliveredCode.get()).status())
                .isEqualTo(ADMIN_LOCKED);

        assertThatThrownBy(() -> issue())
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.VERIFICATION_ADMIN_LOCKED
                );
    }

    private VerificationCheckResult failFiveTimes(
            ChallengeIssueResult issued
    ) {
        String wrong = wrongCode(deliveredCode.get());

        for (int i = 0; i < 4; i++) {
            assertThat(verify(issued, wrong).status())
                    .isEqualTo(WRONG_CODE);
        }

        return verify(issued, wrong);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                currentTime.get(),
                clock.getZone()
        );
    }

    @Test
    void 재발송_실패하면_기존_번호를_계속_사용할_수_있다() {
        ChallengeIssueResult first = issue();
        String originalCode = deliveredCode.get();

        currentTime.updateAndGet(time -> time.plusSeconds(30));

        doThrow(new MailSendException("테스트용 발송 실패"))
                .when(mailSender)
                .sendCode(
                        eq(email),
                        eq(VerificationPurpose.SIGNUP),
                        anyString()
                );

        assertThatThrownBy(() -> issue())
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.VERIFICATION_MAIL_FAILED
                );

        // 재발송 실패로 이전 번호 무효화가 롤백돼야 한다.
        assertThat(verify(first, originalCode).status())
                .isEqualTo(SUCCESS);
    }

    @Test
    void 인증에_성공하면_다음_차단은_다시_5분부터_시작한다() {
        failFiveTimes(issue());

        currentTime.updateAndGet(time -> time.plusSeconds(5 * 60));

        ChallengeIssueResult second = issue();

        assertThat(verify(second, deliveredCode.get()).status())
                .isEqualTo(SUCCESS);

        currentTime.updateAndGet(time -> time.plusSeconds(30));

        VerificationCheckResult blockedAgain = failFiveTimes(issue());

        assertThat(blockedAgain.status()).isEqualTo(TEMPORARILY_BLOCKED);
        assertThat(blockedAgain.blockedUntil())
                .isEqualTo(now().plusMinutes(5));
    }
    @Test
    void 관리자_해제_후에는_인증을_재개하고_5분부터_차단한다() {
        failFiveTimes(issue());

        currentTime.updateAndGet(time -> time.plusSeconds(5 * 60));
        failFiveTimes(issue());

        currentTime.updateAndGet(time -> time.plusSeconds(60 * 60));
        assertThat(failFiveTimes(issue()).status())
                .isEqualTo(ADMIN_LOCKED);

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    var guard = guardService.acquire(
                            email,
                            VerificationPurpose.SIGNUP
                    );

                    guard.unlockByAdmin();
                });

        // 관리자 해제와 별개로 마지막 발송의 30초 간격은 유지
        currentTime.updateAndGet(time -> time.plusSeconds(30));

        VerificationCheckResult blockedAgain = failFiveTimes(issue());

        assertThat(blockedAgain.status()).isEqualTo(TEMPORARILY_BLOCKED);
        assertThat(blockedAgain.blockedUntil())
                .isEqualTo(now().plusMinutes(5));
    }
    @Test
    void 같은_번호를_동시에_확인해도_한_요청만_성공한다()
            throws Exception {

        ChallengeIssueResult issued = issue();
        String code = deliveredCode.get();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<VerificationCheckResult> request = () -> {
            ready.countDown();

            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("시작 신호 대기 시간 초과");
            }

            return verify(issued, code);
        };

        try {
            Future<VerificationCheckResult> first =
                    executor.submit(request);

            Future<VerificationCheckResult> second =
                    executor.submit(request);

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            VerificationCheckResult firstResult =
                    first.get(15, TimeUnit.SECONDS);

            VerificationCheckResult secondResult =
                    second.get(15, TimeUnit.SECONDS);

            assertThat(List.of(
                    firstResult.status(),
                    secondResult.status()
            )).containsExactlyInAnyOrder(
                    SUCCESS,
                    INVALID_CHALLENGE
            );

            VerificationCheckResult success =
                    firstResult.status() == SUCCESS
                            ? firstResult
                            : secondResult;

            assertThat(grantRepository.findByGrantHash(
                    secretHasher.hashGrant(success.grant())
            )).isPresent();

        } finally {
            start.countDown();
            executor.shutdownNow();

            assertThat(executor.awaitTermination(
                    10, TimeUnit.SECONDS
            )).isTrue();
        }
    }
    private <T> List<T> runConcurrently(
            int requestCount,
            Callable<T> request
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);

        try {
            List<Future<T>> futures = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("시작 대기 시간 초과");
                    }

                    return request.call();
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>();

            for (Future<T> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }

            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();

            assertThat(executor.awaitTermination(
                    10, TimeUnit.SECONDS
            )).isTrue();
        }
    }
    @Test
    void 동시에_다섯번_틀려도_횟수가_누락되지_않는다()
            throws Exception {
        ChallengeIssueResult issued = issue();
        String wrong = wrongCode(deliveredCode.get());

        List<VerificationCheckResult> results = runConcurrently(
                5,
                () -> verify(issued, wrong)
        );

        assertThat(results.stream()
                .filter(result -> result.status() == WRONG_CODE)
                .count()).isEqualTo(4);

        assertThat(results.stream()
                .filter(result -> result.status() == TEMPORARILY_BLOCKED)
                .count()).isEqualTo(1);

        assertThat(verify(issued, deliveredCode.get()).status())
                .isEqualTo(TEMPORARILY_BLOCKED);
    }
    @Test
    void 최초_발급이_동시에_들어와도_한번만_발송한다()
            throws Exception {
        List<Boolean> results = runConcurrently(2, () -> {
            try {
                issue();
                return true;
            } catch (BusinessException e) {
                if (e.getErrorCode()
                        != ErrorCode.VERIFICATION_RESEND_TOO_SOON) {
                    throw e;
                }

                return false;
            }
        });

        assertThat(results).containsExactlyInAnyOrder(true, false);

        org.mockito.Mockito.verify(mailSender, times(1)).sendCode(
                eq(email),
                eq(VerificationPurpose.SIGNUP),
                anyString()
        );
    }
}
