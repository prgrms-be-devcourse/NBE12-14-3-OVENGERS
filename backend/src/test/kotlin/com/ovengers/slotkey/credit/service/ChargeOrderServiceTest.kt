package com.ovengers.slotkey.credit.service

import com.ovengers.slotkey.credit.dto.response.ChargeOrderResponse
import com.ovengers.slotkey.credit.entity.ChargeOrder
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus
import com.ovengers.slotkey.credit.repository.ChargeOrderRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.repository.MemberRepository
import com.ovengers.slotkey.support.anyNonNull
import com.ovengers.slotkey.support.captureNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ChargeOrderServiceTest {
    @Mock
    private lateinit var chargeOrderRepository: ChargeOrderRepository

    @Mock
    private lateinit var memberRepository: MemberRepository
    private lateinit var chargeOrderService: ChargeOrderService

    @BeforeEach
    fun setUp() {
        val clock: Clock =
            Clock.fixed(
                Instant.parse("2026-10-13T00:00:00Z"),
                ZoneId.of("Asia/Seoul"),
            )
        chargeOrderService =
            ChargeOrderService(
                chargeOrderRepository,
                memberRepository,
                clock,
            )
    }

    @ParameterizedTest
    @ValueSource(longs = [1_000L, 1_100L, 100_000L])
    @DisplayName("허용된 충전 금액이면 PENDING 주문을 저장한다")
    fun create_validAmount_savesPendingOrder(amount: Long) {
        given(memberRepository.findById(7L)).willReturn(Optional.of(activeMember()))
        given(chargeOrderRepository.save(anyNonNull(ChargeOrder::class.java)))
            .willAnswer({ invocation -> invocation.getArgument(0) })

        val response: ChargeOrderResponse = chargeOrderService.create(7L, amount)

        val captor: ArgumentCaptor<ChargeOrder> = ArgumentCaptor.forClass(ChargeOrder::class.java)
        verify(chargeOrderRepository).save(captureNonNull(captor))
        val saved: ChargeOrder = captor.value

        assertThat(saved.getMemberId()).isEqualTo(7L)
        assertThat(saved.amount).isEqualTo(amount)
        assertThat(saved.status).isEqualTo(ChargeOrderStatus.PENDING)
        assertThat(response.amount).isEqualTo(amount)
        assertThat(response.status).isEqualTo(ChargeOrderStatus.PENDING)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1_000L, 999L, 1_001L, 100_100L])
    @DisplayName("최소·최대·100원 단위 규칙을 벗어난 충전 금액은 거절한다")
    fun create_invalidAmount_throwsValidationFailed(amount: Long) {
        assertThatThrownBy({ chargeOrderService.create(7L, amount) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED)

        verify(chargeOrderRepository, never()).save(anyNonNull(ChargeOrder::class.java))
    }

    @ParameterizedTest
    @ValueSource(longs = [1_000L, 100_000L])
    @DisplayName("생성된 orderId는 chg_와 소문자 UUID v4 형식이고 6자 이상 64자 이하이다")
    fun create_generatesCoreApiCompatibleOrderId(amount: Long) {
        given(memberRepository.findById(7L)).willReturn(Optional.of(activeMember()))
        given(chargeOrderRepository.save(anyNonNull(ChargeOrder::class.java)))
            .willAnswer({ invocation -> invocation.getArgument(0) })

        val response: ChargeOrderResponse = chargeOrderService.create(7L, amount)

        val orderId: String = response.orderId
        assertThat(orderId).matches("^chg_[0-9a-f-]{36}$")
        assertThat(orderId.length).isBetween(6, 64)
        assertThat(UUID.fromString(orderId.substring(4)).version()).isEqualTo(4)
    }

    @Test
    @DisplayName("정지 회원은 충전 주문을 생성할 수 없다")
    fun create_suspendedMember_throwsAccountInactive() {
        val member: Member = activeMember()
        member.updateStatus(MemberStatus.SUSPENDED)
        given(memberRepository.findById(7L)).willReturn(Optional.of(member))

        assertThatThrownBy({ chargeOrderService.create(7L, 1_000L) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_INACTIVE)

        verify(chargeOrderRepository, never()).save(anyNonNull(ChargeOrder::class.java))
    }

    @Test
    @DisplayName("충전 주문 소유자는 현재 주문 상태를 조회한다")
    fun get_ownedOrder_returnsCurrentStatus() {
        val order: ChargeOrder =
            ChargeOrder.createPending(
                7L,
                "chg_550e8400-e29b-41d4-a716-446655440000",
                1_000L,
                java.time.LocalDateTime.of(2026, 10, 13, 9, 0),
            )
        given(chargeOrderRepository.findByOrderId(order.orderId))
            .willReturn(order)

        val response: ChargeOrderResponse =
            chargeOrderService.get(
                7L,
                order.orderId,
            )

        assertThat(response.orderId).isEqualTo(order.orderId)
        assertThat(response.amount).isEqualTo(1_000L)
        assertThat(response.status).isEqualTo(ChargeOrderStatus.PENDING)
    }

    @Test
    @DisplayName("다른 회원의 충전 주문 상태는 조회할 수 없다")
    fun get_otherMembersOrder_throwsForbiddenNotOwner() {
        val order: ChargeOrder =
            ChargeOrder.createPending(
                8L,
                "chg_550e8400-e29b-41d4-a716-446655440000",
                1_000L,
                java.time.LocalDateTime.of(2026, 10, 13, 9, 0),
            )
        given(chargeOrderRepository.findByOrderId(order.orderId))
            .willReturn(order)

        assertThatThrownBy({ chargeOrderService.get(7L, order.orderId) })
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_NOT_OWNER)
    }

    private fun activeMember(): Member = Member("member@test.com", "member")

    @Test
    @DisplayName("내 충전 내역은 PAID·CONFIRMING·FAILED 주문만 조회하고 PENDING(미결제 시도)은 제외한다")
    fun getMyHistory_excludesPendingOrders() {
        val pageable: Pageable = PageRequest.of(0, 10)
        val paid: ChargeOrder =
            ChargeOrder.createPending(
                7L,
                "chg_paid",
                5_000L,
                java.time.LocalDateTime.of(2026, 10, 13, 9, 0),
            )
        paid.markPaid(java.time.LocalDateTime.of(2026, 10, 13, 9, 1))
        paid.recordPaymentMethod("간편결제", "토스페이")
        given(
            chargeOrderRepository.findByMemberIdAndStatusInOrderByIdDesc(
                7L,
                setOf(ChargeOrderStatus.PAID, ChargeOrderStatus.CONFIRMING, ChargeOrderStatus.FAILED),
                pageable,
            ),
        ).willReturn(PageImpl(listOf(paid), pageable, 1))

        val result: Page<com.ovengers.slotkey.credit.dto.response.ChargeOrderHistoryResponse> =
            chargeOrderService.getMyHistory(
                7L,
                pageable,
            )

        assertThat(result.totalElements).isEqualTo(1)
        assertThat(result.content.get(0).orderId).isEqualTo("chg_paid")
        assertThat(result.content.get(0).amount).isEqualTo(5_000L)
        assertThat(result.content.get(0).status).isEqualTo(ChargeOrderStatus.PAID)
        assertThat(result.content.get(0).paymentMethod).contains("간편결제")
        assertThat(result.content.get(0).paymentProvider).contains("토스페이")
    }
}
