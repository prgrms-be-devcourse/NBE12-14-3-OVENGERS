package com.ovengers.slotkey.global.security

import com.ovengers.slotkey.member.entity.MemberRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.ServletWebRequest

class CurrentMemberArgumentResolverTest {
    private val resolver = CurrentMemberArgumentResolver()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    @DisplayName("CurrentMember가 붙은 AuthPrincipal에 현재 인증된 객체를 그대로 주입한다")
    fun injectsAuthenticatedPrincipal() {
        val principal = AuthPrincipal(1L, "principal@example.com", MemberRole.USER)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        SecurityContextHolder.setContext(context)
        val parameter = parameter("annotatedPrincipal", AuthPrincipal::class.java)

        assertThat(resolver.supportsParameter(parameter)).isTrue()
        assertThat(resolver.resolveArgument(parameter, null, ServletWebRequest(MockHttpServletRequest()), null))
            .isSameAs(principal)
    }

    @Test
    @DisplayName("AuthPrincipal 타입이어도 CurrentMember가 없으면 주입 대상으로 선택하지 않는다")
    fun rejectsUnannotatedPrincipal() {
        assertThat(resolver.supportsParameter(parameter("unannotatedPrincipal", AuthPrincipal::class.java))).isFalse()
    }

    @Test
    @DisplayName("CurrentMember가 있어도 AuthPrincipal 타입이 아니면 주입 대상으로 선택하지 않는다")
    fun rejectsAnnotatedDifferentType() {
        assertThat(resolver.supportsParameter(parameter("annotatedString", String::class.java))).isFalse()
    }

    private fun parameter(
        name: String,
        type: Class<*>,
    ): MethodParameter = MethodParameter(ParameterFixture::class.java.getDeclaredMethod(name, type), 0)

    private class ParameterFixture {
        fun annotatedPrincipal(
            @CurrentMember principal: AuthPrincipal,
        ): AuthPrincipal = principal

        fun unannotatedPrincipal(principal: AuthPrincipal): AuthPrincipal = principal

        fun annotatedString(
            @CurrentMember value: String,
        ): String = value
    }
}
