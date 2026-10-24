package com.ovengers.slotkey.space.authorization

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.member.entity.MemberRole
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SpaceAuthorizationServiceTest {
    private val authorizationService = SpaceAuthorizationService()

    @Test
    @DisplayName("ADMIN 역할의 AuthPrincipal은 공간 관리 권한 검증을 정상 통과한다")
    fun validateCanManageSpace_admin_success() {
        // given
        val adminPrincipal = AuthPrincipal(1L, "admin@test.com", MemberRole.ADMIN)

        // when & then
        assertThatCode { authorizationService.validateCanManageSpace(adminPrincipal) }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("USER 역할의 AuthPrincipal은 ACCESS_DENIED 예외가 발생한다")
    fun validateCanManageSpace_user_throwsAccessDenied() {
        // given
        val userPrincipal = AuthPrincipal(2L, "user@test.com", MemberRole.USER)

        // when & then
        assertThatThrownBy { authorizationService.validateCanManageSpace(userPrincipal) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED)
    }

    @Test
    @DisplayName("null principal은 ACCESS_DENIED 예외가 발생한다")
    fun validateCanManageSpace_nullPrincipal_throwsAccessDenied() {
        // when & then
        assertThatThrownBy { authorizationService.validateCanManageSpace(null) }
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED)
    }
}
