package com.ovengers.slotkey.space.authorization

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.member.entity.MemberRole
import org.springframework.stereotype.Service

@Service
class SpaceAuthorizationService {
    /**
     * 요청자가 ADMIN 역할인지 검증한다.
     * null principal 또는 ADMIN 이외의 역할은 ACCESS_DENIED 예외를 발생시킨다.
     */
    fun validateCanManageSpace(principal: AuthPrincipal?) {
        if (principal == null || principal.role != MemberRole.ADMIN) {
            throw BusinessException(ErrorCode.ACCESS_DENIED)
        }
    }
}
