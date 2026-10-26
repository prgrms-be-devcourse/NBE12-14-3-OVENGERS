package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.repository.CreditBalanceRepository
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class CreditServiceImplMutationTest {
    @Mock
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Mock
    private lateinit var creditBalanceRepository: CreditBalanceRepository

    @Mock
    private lateinit var memberRepository: MemberRepository

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    private lateinit var creditService: CreditServiceImpl

    @BeforeEach
    fun setUp() {
        creditService =
            CreditServiceImpl(
                creditTransactionRepository,
                creditBalanceRepository,
                memberRepository,
                reservationRepository,
                Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC),
            )
    }

    @Test
    fun `refund rejects a missing balance row before saving a transaction`() {
        val memberId = 1L
        val reservationId = 10L
        val amount = 1_000
        val member = mock(Member::class.java)
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member))
        given(member.balance).willReturn(10_000)
        given(creditBalanceRepository.increase(memberId, amount)).willReturn(0)

        val exception =
            assertThrows(BusinessException::class.java) {
                creditService.refund(memberId, reservationId, amount)
            }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.MEMBER_NOT_FOUND)
        verify(creditTransactionRepository, never()).save(any(CreditTransaction::class.java))
    }

    @Test
    fun `refund allows a balance exactly equal to the integer maximum`() {
        val memberId = 1L
        val reservationId = 10L
        val amount = 1_000
        val member = mock(Member::class.java)
        val reservation = mock(Reservation::class.java)
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member))
        given(member.balance).willReturn(Int.MAX_VALUE - amount, Int.MAX_VALUE)
        given(creditBalanceRepository.increase(memberId, amount)).willReturn(1)
        given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation))

        val balance =
            assertDoesNotThrow<Int> {
                creditService.refund(memberId, reservationId, amount)
            }

        assertThat(balance).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `refund rejects a balance one greater than the integer maximum`() {
        val memberId = 1L
        val reservationId = 10L
        val amount = 1_000
        val member = mock(Member::class.java)
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member))
        given(member.balance).willReturn(Int.MAX_VALUE - amount + 1)

        val exception =
            assertThrows(BusinessException::class.java) {
                creditService.refund(memberId, reservationId, amount)
            }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.VALIDATION_FAILED)
        verify(creditBalanceRepository, never()).increase(memberId, amount)
        verify(creditTransactionRepository, never()).save(any(CreditTransaction::class.java))
    }

    @Test
    fun `charge ledger references the requested reservation`() {
        val memberId = 1L
        val reservationId = 10L
        val amount = 3_000
        val member = mock(Member::class.java)
        val reservation = mock(Reservation::class.java)
        given(creditBalanceRepository.decreaseIfEnough(memberId, amount)).willReturn(1)
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member))
        given(member.balance).willReturn(7_000)
        given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation))

        creditService.charge(memberId, reservationId, amount)

        assertThat(captureSavedTransaction().reservation).isSameAs(reservation)
    }

    @Test
    fun `refund ledger references the requested reservation`() {
        val memberId = 1L
        val reservationId = 10L
        val amount = 3_000
        val member = mock(Member::class.java)
        val reservation = mock(Reservation::class.java)
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member))
        given(member.balance).willReturn(10_000, 13_000)
        given(creditBalanceRepository.increase(memberId, amount)).willReturn(1)
        given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation))

        creditService.refund(memberId, reservationId, amount)

        assertThat(captureSavedTransaction().reservation).isSameAs(reservation)
    }

    @Test
    fun `penalty ledger references the requested reservation`() {
        val memberId = 1L
        val reservationId = 10L
        val amount = 1_500
        val member = mock(Member::class.java)
        val reservation = mock(Reservation::class.java)
        given(creditBalanceRepository.decreaseIfEnough(memberId, amount)).willReturn(1)
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member))
        given(member.balance).willReturn(8_500)
        given(reservationRepository.findById(reservationId)).willReturn(Optional.of(reservation))

        creditService.penalize(memberId, reservationId, amount)

        assertThat(captureSavedTransaction().reservation).isSameAs(reservation)
    }

    private fun captureSavedTransaction(): CreditTransaction {
        val captor = ArgumentCaptor.forClass(CreditTransaction::class.java)
        verify(creditTransactionRepository).save(captor.capture())
        return captor.value
    }
}
