package com.ovengers.slotkey.credit

import com.ovengers.slotkey.credit.repository.CreditBalanceRepository
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class CreditBalanceConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Autowired
    private lateinit var creditBalanceRepository: CreditBalanceRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    @DisplayName("회원 상태 변경과 크레딧 증가가 동시에 발생해도 최신 잔액을 유지한다")
    fun concurrentStatusChange_doesNotOverwriteUpdatedCreditBalance() {
        val member =
            memberRepository.saveAndFlush(
                Member(
                    "credit-concurrency-${System.nanoTime()}@test.com",
                    "동시성테스트",
                ),
            )

        val memberId = requireNotNull(member.id)

        val initialTx = TransactionTemplate(transactionManager)

        // 테스트 시작 잔액을 10,000으로 설정
        initialTx.executeWithoutResult {
            creditBalanceRepository.increase(
                memberId,
                10000,
            )
        }

        val savedMember =
            memberRepository.findById(memberId).orElseThrow()

        assertThat(savedMember.balance)
            .isEqualTo(10000)

        // 상태 변경 트랜잭션이 Member를 조회한 시점을 확인하기 위한 latch
        val memberLoaded = CountDownLatch(1)

        // 크레딧 증가 트랜잭션이 커밋될 때까지 상태 변경을 대기시키기 위한 latch
        val creditUpdated = CountDownLatch(1)

        val executor: ExecutorService = Executors.newFixedThreadPool(2)

        val statusTx = TransactionTemplate(transactionManager)

        statusTx.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
        )

        val creditTx = TransactionTemplate(transactionManager)

        creditTx.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
        )

        try {
            // 트랜잭션 A:
            // 잔액이 10,000인 Member를 먼저 조회한 뒤 크레딧 증가가 끝날 때까지 대기
            val statusFuture: Future<*> =
                executor.submit(
                    Runnable {
                        statusTx.executeWithoutResult {
                            val loadedMember =
                                memberRepository.findById(memberId).orElseThrow()

                            assertThat(loadedMember.balance)
                                .isEqualTo(10000)

                            memberLoaded.countDown()

                            try {
                                assertThat(
                                    creditUpdated.await(
                                        5,
                                        TimeUnit.SECONDS,
                                    ),
                                ).isTrue()
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw RuntimeException(e)
                            }

                            // 크레딧 증가가 완료된 뒤, 처음 조회한 Member의 상태만 변경
                            loadedMember.updateStatus(
                                MemberStatus.SUSPENDED,
                            )
                        }
                    },
                )

            // 트랜잭션 A가 기존 잔액 10,000을 조회할 때까지 대기
            assertThat(
                memberLoaded.await(
                    5,
                    TimeUnit.SECONDS,
                ),
            ).isTrue()

            // 트랜잭션 B:
            // DB의 잔액을 10,000 → 15,000으로 증가시키고 먼저 커밋
            val creditFuture: Future<*> =
                executor.submit(
                    Runnable {
                        creditTx.executeWithoutResult {
                            creditBalanceRepository.increase(
                                memberId,
                                5000,
                            )
                        }
                    },
                )

            creditFuture.get(
                5,
                TimeUnit.SECONDS,
            )

            // 최신 잔액 15,000이 DB에 반영된 후 상태 변경 트랜잭션을 진행
            creditUpdated.countDown()

            statusFuture.get(
                5,
                TimeUnit.SECONDS,
            )
        } finally {
            // 테스트 실패 시에도 대기 중인 스레드를 해제하고 Executor 종료
            creditUpdated.countDown()
            executor.shutdownNow()
        }

        val result =
            memberRepository.findById(memberId).orElseThrow()

        // 상태 변경은 정상 반영되어야 함
        assertThat(result.status)
            .isEqualTo(MemberStatus.SUSPENDED)

        // 상태 변경이 최신 크레딧 잔액 15,000을 덮어쓰면 안 됨
        assertThat(result.balance)
            .isEqualTo(15000)
    }
}
