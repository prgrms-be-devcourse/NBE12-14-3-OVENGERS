package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.credit.repository.CreditBalanceRepository
import com.ovengers.slotkey.credit.repository.CreditTransactionRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.repository.MemberRepository
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
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
class CreditGrantServiceTest {
    @Mock
    private lateinit var creditBalanceRepository: CreditBalanceRepository

    @Mock
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Mock
    private lateinit var memberRepository: MemberRepository

    private lateinit var clock: Clock
    private lateinit var creditGrantService: CreditGrantService

    @BeforeEach
    fun setUp() {
        clock =
            Clock.fixed(
                Instant.parse("2026-09-17T00:00:00Z"),
                ZoneId.of("Asia/Seoul"),
            )

        creditGrantService =
            CreditGrantService(
                creditBalanceRepository,
                creditTransactionRepository,
                memberRepository,
                clock,
            )
    }

    // 회원가입 지급 성공
    @Test
    @DisplayName("회원가입 크레딧 지급 성공 시 거래 내역을 저장한다")
    fun grantSignupCredit_success() {
        // given
        val memberId = 1L
        val amount = 10000

        val member = Mockito.mock(Member::class.java)

        BDDMockito
            .given(creditBalanceRepository.increase(memberId, amount))
            .willReturn(1)
        BDDMockito
            .given(memberRepository.findById(memberId))
            .willReturn(Optional.of(member))
        BDDMockito
            .given(member.balance)
            .willReturn(
                0,
                10000,
            )

        // when
        val balance =
            creditGrantService.grantSignupCredit(
                memberId,
                amount,
            )

        // then
        Assertions.assertThat(balance).isEqualTo(10000)

        val captor =
            ArgumentCaptor.forClass(CreditTransaction::class.java)

        Mockito
            .verify(creditTransactionRepository)
            .save(captor.capture())

        val saved = captor.value

        Assertions.assertThat(saved.amount).isEqualTo(10000)
        Assertions
            .assertThat(saved.type)
            .isEqualTo(CreditTransactionType.SIGNUP_GRANT)
        Assertions.assertThat(saved.balanceAfter).isEqualTo(10000)
        Assertions.assertThat(saved.reservation).isNull()
        Assertions.assertThat(saved.reason).isNull()
    }

    // 관리자 지급 성공
    @Test
    @DisplayName("관리자 크레딧 지급 성공 시 지급 사유와 거래 내역을 저장한다")
    fun grantAdminCredit_success() {
        // given
        val memberId = 1L
        val amount = 5000
        val reason = "이벤트 보상"

        val member = Mockito.mock(Member::class.java)

        BDDMockito
            .given(creditBalanceRepository.increase(memberId, amount))
            .willReturn(1)
        BDDMockito
            .given(memberRepository.findById(memberId))
            .willReturn(Optional.of(member))
        BDDMockito
            .given(member.balance)
            .willReturn(
                10000,
                15000,
            )

        // when
        val balance =
            creditGrantService.grantAdminCredit(
                memberId,
                amount,
                reason,
            )

        // then
        Assertions.assertThat(balance).isEqualTo(15000)

        val captor =
            ArgumentCaptor.forClass(CreditTransaction::class.java)

        Mockito
            .verify(creditTransactionRepository)
            .save(captor.capture())

        val saved = captor.value

        Assertions.assertThat(saved.amount).isEqualTo(5000)
        Assertions
            .assertThat(saved.type)
            .isEqualTo(CreditTransactionType.ADMIN_GRANT)
        Assertions.assertThat(saved.balanceAfter).isEqualTo(15000)
        Assertions.assertThat(saved.reason).isEqualTo(reason)
    }

    // 잘못된 지급액
    @ParameterizedTest
    @ValueSource(ints = [0, -1000])
    @DisplayName("0 이하의 금액은 크레딧 지급을 거절한다")
    fun invalidAmount_throwsException(amount: Int) {
        // given
        val memberId = 1L

        // when
        val signupException =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditGrantService.grantSignupCredit(
                    memberId,
                    amount,
                )
            }

        val adminException =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditGrantService.grantAdminCredit(
                    memberId,
                    amount,
                    "관리자 지급",
                )
            }

        // then
        Assertions
            .assertThat(signupException.errorCode)
            .isEqualTo(ErrorCode.VALIDATION_FAILED)
        Assertions
            .assertThat(adminException.errorCode)
            .isEqualTo(ErrorCode.VALIDATION_FAILED)

        Mockito.verifyNoInteractions(creditBalanceRepository)
    }

    // 지급 대상 회원 없음
    @Test
    @DisplayName("지급 대상 회원이 없으면 MEMBER_NOT_FOUND 예외가 발생한다")
    fun memberNotFound() {
        // given
        val memberId = 1L
        val amount = 10000

        BDDMockito
            .given(memberRepository.findById(memberId))
            .willReturn(Optional.empty())

        // when
        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditGrantService.grantSignupCredit(
                    memberId,
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

    // 지급 후 잔액 최대값 초과
    @Test
    @DisplayName("지급 후 잔액이 int 최대값을 초과하면 지급을 거절한다")
    fun balanceOverflow_throwsException() {
        // given
        val memberId = 1L
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
                creditGrantService.grantAdminCredit(
                    memberId,
                    amount,
                    "관리자 지급",
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

    // 관리자 지급 사유 누락
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["   "])
    @DisplayName("관리자 지급 사유가 없으면 지급을 거절한다")
    fun invalidReason_throwsException(reason: String?) {
        // given
        val memberId = 1L
        val amount = 5000

        // when
        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditGrantService.grantAdminCredit(
                    memberId,
                    amount,
                    reason,
                )
            }

        // then
        Assertions
            .assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.VALIDATION_FAILED)

        Mockito.verifyNoInteractions(creditBalanceRepository)
    }

    // 관리자 지급 사유 501자 거절
    @Test
    @DisplayName("관리자 지급 사유가 500자를 초과하면 지급을 거절한다")
    fun reasonOver500Characters_throwsException() {
        // given
        val memberId = 1L
        val amount = 5000
        val reason = "a".repeat(501)

        // when
        val exception =
            org.junit.jupiter.api.Assertions.assertThrows(BusinessException::class.java) {
                creditGrantService.grantAdminCredit(
                    memberId,
                    amount,
                    reason,
                )
            }

        // then
        Assertions
            .assertThat(exception.errorCode)
            .isEqualTo(ErrorCode.VALIDATION_FAILED)

        Mockito.verifyNoInteractions(creditBalanceRepository)
    }

    // 관리자 지급 사유 500자 허용
    @Test
    @DisplayName("관리자 지급 사유가 500자이면 정상적으로 지급한다")
    fun reason500Characters_success() {
        // given
        val memberId = 1L
        val amount = 5000
        val reason = "a".repeat(500)

        val member = Mockito.mock(Member::class.java)

        BDDMockito
            .given(creditBalanceRepository.increase(memberId, amount))
            .willReturn(1)
        BDDMockito
            .given(memberRepository.findById(memberId))
            .willReturn(Optional.of(member))
        BDDMockito
            .given(member.balance)
            .willReturn(
                0,
                5000,
            )

        // when
        val balance =
            creditGrantService.grantAdminCredit(
                memberId,
                amount,
                reason,
            )

        // then
        Assertions.assertThat(balance).isEqualTo(5000)

        Mockito
            .verify(creditTransactionRepository)
            .save(ArgumentMatchers.any(CreditTransaction::class.java))
    }
}
