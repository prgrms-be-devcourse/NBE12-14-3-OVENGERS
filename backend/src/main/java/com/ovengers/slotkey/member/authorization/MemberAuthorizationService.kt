package com.ovengers.slotkey.member.authorization

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.member.entity.MemberRole
import org.springframework.stereotype.Service

@Service
class MemberAuthorizationService {
    /**
     * 요청자가 ADMIN 역할인지 검증한다.
     * null principal 또는 ADMIN 이외의 역할은 FORBIDDEN_ROLE 예외를 발생시킨다.
     */
    fun validateCanManageMember(principal: AuthPrincipal?) {
        if (principal?.role() != MemberRole.ADMIN) {
            throw BusinessException(ErrorCode.FORBIDDEN_ROLE)
        }
    }
}
