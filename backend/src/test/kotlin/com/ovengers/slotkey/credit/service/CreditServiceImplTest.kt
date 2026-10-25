package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.credit.repository.CreditBalanceRepository
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.reservation.entity.Reservation
import com.ovengers.slotkey.reservation.repository.ReservationRepository
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Optional

@ExtendWith(MockitoExtension::class)
@Suppress("ktlint:standard:function-naming")
class CreditServiceImplTest {
    @Mock
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Mock
    private lateinit var creditBalanceRepository: CreditBalanceRepository

    @Mock
    private lateinit var memberRepository: MemberRepository

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    private lateinit var clock: Clock
    private lateinit var creditService: CreditServiceImpl

    @BeforeEach
    fun setUp() {
        clock =
            Clock.fixed(
                Instant.parse("2026-09-17T00:00:00Z"),
                ZoneId.of("Asia/Seoul"),
            )

        creditService =
            CreditServiceImpl(
                creditTransactionRepository,
                creditBalanceRepository,
                memberRepository,
                reservationRepository,
                clock,
            )
    }

    // 결제 성공
    @Test
    @DisplayName("결제 성공 시 크레딧을 차감하고 거래 내역을 저장한다")
    fun charge_success() {
        // given
        val memberId = 1L
        val reservationId = 10L
        val amount = 3000

        val member = Mockito.mock(Member::class.java)
        val reservation = Mockito.mock(Reservation::class.java)

        BDDMockito
            .given(creditBalanceRepository.decreaseIfEnough(memberId, amount))
            .willReturn(1)
        BDDMockito
            .given(memberRepository.findById(memberId))
            .willReturn(Optional.of(member))
        BDDMockito
            .given(reservationRepository.findById(reservationId))
            .willReturn(Optional.of(reservation))
        BDDMockito
            .given(member.balance)
            .willReturn(7000)

        // when
        val balance =
            creditService.charge(
                memberId,
                reservationId,
                amount,
            )

        // then
        Assertions.assertThat(balance).isEqualTo(7000)

        Mockito
            .verify(creditBalanceRepository)
            .decreaseIfEnough(memberId, amount)

        val captor =
            ArgumentCaptor.forClass(CreditTransaction::class.java)

        Mockito
            .verify(creditTransactionRepository)
            .save(captor.capture())

        val saved = captor.value

        Assertions.assertThat(saved.amount).isEqualTo(-3000)
        Assertions
            .assertThat(saved.type)
            .isEqualTo(CreditTransactionType.RESERVATION_CHARGE)
        Assertions.assertThat(saved.balanceAfter).isEqualTo(7000)
    }

    // 환급 성공
    @Test
    @DisplayName("환급 성공 시 크레딧을 증가시키고 거래 내역을 저장한다")
    fun refund_success() {
        // given
        val memberId = 1L
        val reservationId = 10L
        val amount = 3000

        val member = Mockito.mock(Member::class.java)
        val reservation = Mockito.mock(Reservation::class.java)

        BDDMockito
            .given(creditBalanceRepository.increase(memberId, amount))
            .willReturn(1)
        BDDMockito
            .given(memberRepository.findById(memberId))
            .willReturn(Optional.of(member))
        BDDMockito
            .given(reservationRepository.findById(reservationId))
            .willReturn(Optional.of(reservation))
        BDDMockito
            .given(member.balance)
            .willReturn(
                10000,
                13000,
            )

        // when
        val balance =
            creditService.refund(
                memberId,
                reservationId,
                amount,
            )

        // then
        Assertions.assertThat(balance).isEqualTo(13000)

        Mockito
            .verify(creditBalanceRepository)
            .increase(memberId, amount)

        val captor =
            ArgumentCaptor.forClass(CreditTransaction::class.java)

        Mockito
            .verify(creditTransactionRepository)
            .save(captor.capture())

        val saved = captor.value

        Assertions.assertThat(saved.amount).isEqualTo(3000)
        Assertions
            .assertThat(saved.type)
            .isEqualTo(CreditTransactionType.REFUND)
        Assertions.assertThat(saved.balanceAfter).isEqualTo(13000)
    }

    // 위약금 성공
    @Test
    @DisplayName("위약금 차감 성공 시 크레딧을 차감하고 거래 내역을 저장한다")
    fun penalize_success() {
        // given
        val memberId = 1L
        val reservationId = 10L
        val amount = 1500

        val member = Mockito.mock(Member::class.java)
        val reservation = Mockito.mock(Reservation::class.java)

        BDDMockito
            .given(creditBalanceRepository.decreaseIfEnough(memberId, amount))
            .willReturn(1)
        BDDMockito
            .given(memberRepository.findById(memberId))
            .willReturn(Optional.of(member))
        BDDMockito
            .given(reservationRepository.findById(reservationId))
            .willReturn(Optional.of(reservation))
        BDDMockito
            .given(member.balance)
            .willReturn(8500)

        // when
        val balance =
            creditService.penalize(
                memberId,
                reservationId,
                amount,
            )

        // then
        Assertions.assertThat(balance).isEqualTo(8500)

        Mockito
            .verify(creditBalanceRepository)
            .decreaseIfEnough(memberId, amount)

        val captor =
            ArgumentCaptor.forClass(CreditTransaction::class.java)

        Mockito
            .verify(creditTransactionRepository)
            .save(captor.capture())

        val saved = captor.value

        Assertions.assertThat(saved.amount).isEqualTo(-1500)
        Assertions
            .assertThat(saved.type)
            .isEqualTo(CreditTransactionType.PENALTY)
        Assertions.assertThat(saved.balanceAfter).isEqualTo(8500)
    }

    // 결제 잔액 부족
    @Test
    @DisplayName("결제 시 잔액이 부족하면 INSUFFICIENT_BALANCE 예외가 발생한다")
    fun charge_insufficientBalance() {
        // given
        val memberId = 1L
        val reservationId = 10L
        val amount = 3000

        BDDMockito
            .given(creditBalanceRepository.decreaseIfEnough(memberId, amount))
            .willReturn(0)

        // when
        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditService.charge(
                    memberId,
                    reservationId,
                    amount,
                )
            }

        // then
        Assertions
            .assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE)

        Mockito
            .verify(creditTransactionRepository, Mockito.never())
            .save(ArgumentMatchers.any(CreditTransaction::class.java))
    }

    // 위약금 잔액 부족
    @Test
    @DisplayName("위약금 차감 시 잔액이 부족하면 INSUFFICIENT_BALANCE 예외가 발생한다")
    fun penalize_insufficientBalance() {
        // given
        val memberId = 1L
        val reservationId = 10L
        val amount = 1500

        BDDMockito
            .given(creditBalanceRepository.decreaseIfEnough(memberId, amount))
            .willReturn(0)

        // when
        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditService.penalize(
                    memberId,
                    reservationId,
                    amount,
                )
            }

        // then
        Assertions
            .assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE)

        Mockito
            .verify(creditTransactionRepository, Mockito.never())
            .save(ArgumentMatchers.any(CreditTransaction::class.java))
    }

    // 잘못된 금액
    @ParameterizedTest
    @ValueSource(ints = [0, -1000])
    @DisplayName("0 이하의 금액은 모든 크레딧 처리를 거절한다")
    fun invalidAmount_throwsException(amount: Int) {
        // given
        val memberId = 1L
        val reservationId = 10L

        // when
        val chargeException =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditService.charge(
                    memberId,
                    reservationId,
                    amount,
                )
            }

        val refundException =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditService.refund(
                    memberId,
                    reservationId,
                    amount,
                )
            }

        val penalizeException =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditService.penalize(
                    memberId,
                    reservationId,
                    amount,
                )
            }

        // then
        Assertions
            .assertThat(chargeException.errorCode)
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
        Assertions
            .assertThat(refundException.errorCode)
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
        Assertions
            .assertThat(penalizeException.errorCode)
            .isEqualTo(ErrorCode.VALIDATION_FAILED)

        Mockito.verifyNoInteractions(creditBalanceRepository)
    }

    // 환급 대상 회원 없음
    @Test
    @DisplayName("환급 대상 회원이 없으면 MEMBER_NOT_FOUND 예외가 발생한다")
    fun refund_memberNotFound() {
        // given
        val memberId = 1L
        val reservationId = 10L
        val amount = 3000

        BDDMockito
            .given(memberRepository.findById(memberId))
            .willReturn(Optional.empty())

        // when
        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditService.refund(
                    memberId,
                    reservationId,
                    amount,
                )
            }

        // then
        Assertions
            .assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.MEMBER_NOT_FOUND)

        Mockito
            .verify(creditBalanceRepository, Mockito.never())
            .increase(memberId, amount)

        Mockito
            .verify(creditTransactionRepository, Mockito.never())
            .save(ArgumentMatchers.any(CreditTransaction::class.java))
    }

    // 환불 후 잔액 최대값 초과
    @Test
    @DisplayName("환불 후 잔액이 int 최대값을 초과하면 환불을 거절한다")
    fun refund_balanceOverflow_throwsException() {
        // given
        val memberId = 1L
        val reservationId = 10L
        val amount = 1000

        val member = Mockito.mock(Member::class.java)

        BDDMockito
            .given(memberRepository.findById(memberId))
            .willReturn(Optional.of(member))
        BDDMockito
            .given(member.balance)
            .willReturn(Int.MAX_VALUE - 500)

        // when
        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditService.refund(
                    memberId,
                    reservationId,
                    amount,
                )
            }

        // then
        Assertions
            .assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.VALIDATION_FAILED)

        Mockito
            .verify(creditBalanceRepository, Mockito.never())
            .increase(memberId, amount)

        Mockito
            .verify(creditTransactionRepository, Mockito.never())
            .save(ArgumentMatchers.any(CreditTransaction::class.java))
    }

    // 예약 조회 실패
    @Test
    @DisplayName("예약을 찾을 수 없으면 RESERVATION_NOT_FOUND 예외가 발생한다")
    fun reservationNotFound() {
        // given
        val memberId = 1L
        val reservationId = 10L
        val amount = 3000

        val member = Mockito.mock(Member::class.java)

        BDDMockito
            .given(creditBalanceRepository.decreaseIfEnough(memberId, amount))
            .willReturn(1)
        BDDMockito
            .given(memberRepository.findById(memberId))
            .willReturn(Optional.of(member))
        BDDMockito
            .given(reservationRepository.findById(reservationId))
            .willReturn(Optional.empty())

        // when
        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditService.charge(
                    memberId,
                    reservationId,
                    amount,
                )
            }

        // then
        Assertions
            .assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND)

        Mockito
            .verify(creditTransactionRepository, Mockito.never())
            .save(ArgumentMatchers.any(CreditTransaction::class.java))
    }
}
