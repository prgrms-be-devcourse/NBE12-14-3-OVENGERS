package com.ovengers.slotkey.global.security

import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberRole
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

// 이번 요청을 보낸 회원의 정보를 담는 객체
// TODO: Java 호출부·테스트 전환 완료 후 record 접근자 사용과 JSON 호환을 확인하고 @JvmRecord 제거를 검토한다.
@JvmRecord
data class AuthPrincipal(
    val memberId: Long?,
    val email: String?,
    val role: MemberRole?,
) {
    constructor(member: Member) : this(
        member.id,
        member.email,
        member.role,
    )

    fun getAuthorities(): Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_${role!!.name}"))
}
