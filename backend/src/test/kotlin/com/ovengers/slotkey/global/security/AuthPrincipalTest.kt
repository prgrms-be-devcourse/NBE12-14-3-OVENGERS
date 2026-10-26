package com.ovengers.slotkey.global.security

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AuthPrincipalTest {
    @Test
    @DisplayName("회원 정보와 USER·ADMIN 권한을 Principal로 변환하며 큰 ID를 유지한다")
    fun mapsMemberIdentityAndRoleAuthorities() {
        for (role in MemberRole.values()) {
            val member = mock(Member::class.java)
            `when`(member.id).thenReturn(2_147_483_648L)
            `when`(member.email).thenReturn("principal@example.com")
            `when`(member.role).thenReturn(role)

            val principal = AuthPrincipal(member)
            assertThat(principal.memberId).isEqualTo(2_147_483_648L)
            assertThat(principal.email).isEqualTo("principal@example.com")
            assertThat(principal.role).isEqualTo(role)
            assertThat(principal.getAuthorities().map { it.authority }).containsExactly("ROLE_${role.name}")
        }
    }
}
