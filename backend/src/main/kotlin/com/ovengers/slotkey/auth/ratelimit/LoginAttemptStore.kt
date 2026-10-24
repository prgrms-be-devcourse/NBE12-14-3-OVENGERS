
package com.ovengers.slotkey.auth.ratelimit

/**
 * 고정된 시간 구간에서 로그인 시도 횟수를 소비하기 위한 저장소 인터페이스.
 */
interface LoginAttemptStore {
    /**
     * 현재 시간 구간의 시도 횟수가 제한 미만이면
     * 원자적으로 횟수를 증가시키고 true를 반환한다.
     *
     * 제한에 도달했다면 증가시키지 않고 false를 반환한다.
     *
     * 이메일 키는 member.email과 동일한 DB Collation을 사용한다.
     */
    fun tryAcquire(
        email: String,
        windowId: Long,
        limit: Int,
    ): Boolean
}
