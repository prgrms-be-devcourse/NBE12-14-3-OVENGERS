package com.ovengers.slotkey.access.authorization

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.springframework.stereotype.Service

@Service
class DoorAccessAuthorizationService {
    // 로그인 회원과 예약 회원이 같은지 확인한다. 기존 Objects.equals의 null 비교도 유지한다.
    fun isOwner(
        loginMemberId: Long?,
        reservationMemberId: Long?,
    ): Boolean = loginMemberId == reservationMemberId

    fun validateOwner(
        loginMemberId: Long?,
        reservationMemberId: Long?,
    ) {
        if (!isOwner(loginMemberId, reservationMemberId)) {
            throw BusinessException(ErrorCode.FORBIDDEN_NOT_OWNER)
        }
    }
}
