package com.ovengers.slotkey.access.service

import com.ovengers.slotkey.access.entity.AccessDenyReason
import com.ovengers.slotkey.access.entity.AccessResult
import com.ovengers.slotkey.access.entity.DoorAccessLog
import com.ovengers.slotkey.access.repository.DoorAccessLogRepository
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import com.ovengers.slotkey.member.repository.MemberRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class InactiveMemberAccessLogService(
    private val memberRepository: MemberRepository,
    private val doorAccessLogRepository: DoorAccessLogRepository,
) {
    // 인증 단계에서 비활성으로 판정된 회원의 출입 시도 기록
    // 상위 트랜잭션이 롤백되어도 거절 로그가 남도록 별도 트랜잭션으로 저장한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        memberId: Long?,
        attemptedAt: LocalDateTime?,
    ) {
        val member =
            memberRepository.findByIdOrNull(requireNotNull(memberId) { "The given id must not be null" })
                ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)

        doorAccessLogRepository.save(
            DoorAccessLog(
                actorMember = member,
                reservation = null,
                requestedSpace = null,
                result = AccessResult.DENY,
                reasonCode = AccessDenyReason.MEMBER_INACTIVE,
                attemptedAt = attemptedAt,
            ),
        )
    }
}
