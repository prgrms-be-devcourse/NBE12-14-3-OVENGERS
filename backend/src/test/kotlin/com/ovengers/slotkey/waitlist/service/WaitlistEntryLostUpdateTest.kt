package com.ovengers.slotkey.waitlist.service

import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import com.ovengers.slotkey.waitlist.entity.WaitlistEntry
import com.ovengers.slotkey.waitlist.entity.WaitlistStatus
import com.ovengers.slotkey.waitlist.repository.WaitlistEntryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 4-0: 취소(cancel)와 알림 시각 기록(markNotified)이 같은 대기 행에서 겹칠 때 서로를 덮어쓰지 않는지.
 *
 * 시나리오: 스레드 A가 엔터티를 먼저 읽는다(lastNotifiedAt = null 스냅샷) -> 스레드 B가 알림 시각을 기록하고
 * 커밋한다 -> A가 cancel()하고 커밋한다. 전체 컬럼 UPDATE면 A의 flush가 B가 쓴 last_notified_at을
 * 스냅샷 값(null)으로 되돌린다(lost update, step-1-review.md M3).
 * 래치로 순서를 고정하므로 타이밍 운이 아니라 매번 같은 결과가 나온다.
 */
class WaitlistEntryLostUpdateTest : ReservationIntegrationTestSupport() {
    @Autowired
    private lateinit var waitlistEntryRepository: WaitlistEntryRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    @DisplayName("취소가 먼저 읽은 뒤 알림 시각이 기록돼도, 취소 커밋이 last_notified_at을 되돌리지 않는다")
    fun cancel_afterConcurrentMarkNotified_keepsLastNotifiedAt() {
        val memberId = createMember(0)
        val spaceId = createSpace()
        val start = tomorrowAt(14, 0)
        val entryId =
            requireNotNull(
                waitlistEntryRepository
                    .saveAndFlush(
                        WaitlistEntry.waiting(memberId, spaceId, start, start.plusHours(1), now()),
                    ).id,
            )
        val notifiedAt = now().plusMinutes(1)

        val tx = TransactionTemplate(transactionManager)
        val aHasRead = CountDownLatch(1)
        val bCommitted = CountDownLatch(1)

        val a =
            CompletableFuture.runAsync {
                tx.executeWithoutResult {
                    val entry = waitlistEntryRepository.findById(entryId).orElseThrow()
                    aHasRead.countDown()
                    await(bCommitted)
                    entry.cancel()
                }
            }
        val b =
            CompletableFuture.supplyAsync {
                await(aHasRead)
                val updated =
                    tx.execute {
                        waitlistEntryRepository.markNotified(entryId, notifiedAt, WaitlistStatus.WAITING)
                    }
                bCommitted.countDown()
                updated
            }

        assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(1)
        a.get(10, TimeUnit.SECONDS)

        val result = waitlistEntryRepository.findById(entryId).orElseThrow()
        assertThat(result.status).isEqualTo(WaitlistStatus.CANCELLED)
        assertThat(result.active).isNull()
        assertThat(result.lastNotifiedAt).isEqualTo(notifiedAt)
    }

    @Test
    @DisplayName("이미 취소된 대기 행에는 알림 시각을 기록하지 않는다(0건 갱신)")
    fun markNotified_onCancelledEntry_updatesNothing() {
        val memberId = createMember(0)
        val spaceId = createSpace()
        val start = tomorrowAt(14, 0)
        val entry = WaitlistEntry.waiting(memberId, spaceId, start, start.plusHours(1), now())
        entry.cancel()
        val entryId = requireNotNull(waitlistEntryRepository.saveAndFlush(entry).id)

        val updated =
            TransactionTemplate(transactionManager).execute {
                waitlistEntryRepository.markNotified(entryId, now(), WaitlistStatus.WAITING)
            }!!

        assertThat(updated).isZero()
        assertThat(waitlistEntryRepository.findById(entryId).orElseThrow().lastNotifiedAt).isNull()
    }

    private fun await(latch: CountDownLatch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("latch timeout")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(e)
        }
    }
}
