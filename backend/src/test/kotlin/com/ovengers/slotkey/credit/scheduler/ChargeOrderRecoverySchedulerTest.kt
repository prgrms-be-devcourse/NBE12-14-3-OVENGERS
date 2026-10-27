package com.ovengers.slotkey.credit.scheduler

import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.repository.ChargeOrderRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

@ExtendWith(MockitoExtension::class)
class ChargeOrderRecoverySchedulerTest {
    @Mock
    private lateinit var chargeOrderRepository: ChargeOrderRepository

    @Mock
    private lateinit var processor: ChargeOrderRecoveryProcessor
    private lateinit var scheduler: ChargeOrderRecoveryScheduler

    @BeforeEach
    fun setUp() {
        val clock: Clock =
            Clock.fixed(
                NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                ZoneId.of("Asia/Seoul"),
            )
        scheduler =
            ChargeOrderRecoveryScheduler(
                chargeOrderRepository,
                processor,
                clock,
                BATCH_SIZE,
            )
    }

    @Test
    @DisplayName("한 주기에 24시간 초과 50건과 PG 조회 후보 50건을 각각 처리한다")
    fun recoverChargeOrders_usesSeparateLimitsForExhaustedAndLookupCandidates() {
        val firstFifty: PageRequest = PageRequest.of(0, BATCH_SIZE)
        given(
            chargeOrderRepository.findRecoveryExhaustedCandidateIds(
                ChargeOrderStatus.CONFIRMING,
                NOW.minusHours(24),
                firstFifty,
            ),
        ).willReturn(listOf(1L, 2L))
        given(
            chargeOrderRepository.findRecoveryCandidateIds(
                ChargeOrderStatus.CONFIRMING,
                NOW,
                NOW.minusHours(24),
                firstFifty,
            ),
        ).willReturn(listOf(3L, 4L))

        scheduler.recoverChargeOrders()

        verify(processor).markExpired(1L, NOW)
        verify(processor).markExpired(2L, NOW)
        verify(processor).recover(3L, NOW)
        verify(processor).recover(4L, NOW)
    }

    @Test
    @DisplayName("한 주문의 복구 예외가 뒤 주문 처리를 막지 않는다")
    fun recoverChargeOrders_oneFailure_doesNotStopRemainingOrders() {
        val firstFifty: PageRequest = PageRequest.of(0, BATCH_SIZE)
        given(
            chargeOrderRepository.findRecoveryExhaustedCandidateIds(
                ChargeOrderStatus.CONFIRMING,
                NOW.minusHours(24),
                firstFifty,
            ),
        ).willReturn(listOf())
        given(
            chargeOrderRepository.findRecoveryCandidateIds(
                ChargeOrderStatus.CONFIRMING,
                NOW,
                NOW.minusHours(24),
                firstFifty,
            ),
        ).willReturn(listOf(3L, 4L))
        doThrow(IllegalStateException("first failed"))
            .`when`(processor)
            .recover(3L, NOW)

        scheduler.recoverChargeOrders()

        verify(processor).recover(3L, NOW)
        verify(processor).recover(4L, NOW)
    }

    companion object {
        private val NOW = LocalDateTime.of(2026, 10, 14, 12, 0)
        private const val BATCH_SIZE = 50
    }
}
