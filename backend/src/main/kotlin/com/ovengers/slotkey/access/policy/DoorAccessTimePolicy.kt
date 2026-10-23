package com.ovengers.slotkey.access.policy

import com.ovengers.slotkey.access.entity.AccessDenyReason
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class DoorAccessTimePolicy {
    // 예약 종료 전까지 토큰 발급 가능
    fun canIssueToken(
        now: LocalDateTime,
        endAt: LocalDateTime,
    ): Boolean = now.isBefore(endAt)

    // 현재 시각 기준으로 출입 거절 사유 확인
    // 거절 사유가 없으면 null
    fun findDenyReason(
        now: LocalDateTime,
        startAt: LocalDateTime,
        endAt: LocalDateTime,
        checkedInAt: LocalDateTime?,
    ): AccessDenyReason? {
        // 예약 종료 시각부터 출입 거절
        if (!now.isBefore(endAt)) {
            return AccessDenyReason.OUTSIDE_ALLOWED_TIME
        }

        return if (checkedInAt == null) {
            findFirstCheckInDenyReason(now, startAt)
        } else {
            findReentryDenyReason(now, checkedInAt)
        }
    }

    private fun findFirstCheckInDenyReason(
        now: LocalDateTime,
        startAt: LocalDateTime,
    ): AccessDenyReason? {
        val checkInCloseAt = startAt.plusMinutes(FIRST_CHECK_IN_MINUTES)

        // 최초 체크인은 예약 시작부터 15분 후까지 허용
        if (now.isBefore(startAt) || now.isAfter(checkInCloseAt)) {
            return AccessDenyReason.OUTSIDE_ALLOWED_TIME
        }

        return null
    }

    private fun findReentryDenyReason(
        now: LocalDateTime,
        checkedInAt: LocalDateTime,
    ): AccessDenyReason? {
        // 최초 체크인 시각과 같거나 이전이면 출입 거절
        if (!now.isAfter(checkedInAt)) {
            return AccessDenyReason.OUTSIDE_ALLOWED_TIME
        }

        return null
    }

    private companion object {
        private const val FIRST_CHECK_IN_MINUTES = 15L
    }
}
