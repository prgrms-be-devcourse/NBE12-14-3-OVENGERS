package com.ovengers.slotkey.access.authorization

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DoorAccessAuthorizationServiceTest {
    private val service = DoorAccessAuthorizationService()

    @Test
    @DisplayName("로그인 회원과 예약 회원이 같으면 예약 소유자이다")
    fun shouldReturnTrueWhenMemberIsReservationOwner() {
        val result = service.isOwner(1L, 1L)
        assertThat(result).isTrue()
    }

    @Test
    @DisplayName("로그인 회원과 예약 회원이 다르면 예약 소유자가 아니다")
    fun shouldReturnFalseWhenMemberIsNotReservationOwner() {
        val result = service.isOwner(1L, 2L)
        assertThat(result).isFalse()
    }

    @Test
    @DisplayName("예약 소유자가 일치하면 예외가 발생하지 않는다")
    fun shouldNotThrowWhenMemberIsReservationOwner() {
        assertThatCode { service.validateOwner(1L, 1L) }.doesNotThrowAnyException()
    }

    @Test
    @DisplayName("예약 소유자가 다르면 접근 권한 예외가 발생한다")
    fun shouldThrowWhenMemberIsNotReservationOwner() {
        assertThatThrownBy { service.validateOwner(1L, 2L) }
            .isInstanceOfSatisfying(BusinessException::class.java) { exception ->
                assertThat(exception.errorCode).isEqualTo(ErrorCode.FORBIDDEN_NOT_OWNER)
            }
    }
}
