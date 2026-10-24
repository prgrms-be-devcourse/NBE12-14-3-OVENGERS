
package com.ovengers.slotkey.auth.ratelimit

import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class LoginRateLimitService(
    private val loginAttemptStore: LoginAttemptStore,
    private val clock: Clock,
) {
    companion object {
        const val MAX_ATTEMPTS = 10
        const val WINDOW_SECONDS = 15 * 60L

        private val log = LoggerFactory.getLogger(LoginRateLimitService::class.java)
    }

    /**
     * 고정된 15분 시간 구간에서 로그인 시도 슬롯을 확보한다.
     *
     * 최대 10회까지 허용하며, 제한에 도달하면
     * 다음 시간 구간까지 남은 대기 시간을 반환한다.
     *
     * 저장소 오류가 발생하면 로그인을 허용하지 않고
     * 인증 서비스 사용 불가 예외를 발생시킨다.
     */
    fun acquire(email: String): AttemptDecision {
        val now = clock.instant()
        val windowId = Math.floorDiv(now.epochSecond, WINDOW_SECONDS)

        // 별도 트랜잭션에서 로그인 시도 횟수 확인 및 증가
        val allowed =
            try {
                loginAttemptStore.tryAcquire(
                    email,
                    windowId,
                    MAX_ATTEMPTS,
                )
            } catch (e: DataAccessException) {
                log.warn(
                    "Login rate-limit storage unavailable ({})",
                    e.javaClass.simpleName,
                )
                throw BusinessException(ErrorCode.AUTHENTICATION_SERVICE_UNAVAILABLE)
            } catch (e: TransactionException) {
                log.warn(
                    "Login rate-limit storage unavailable ({})",
                    e.javaClass.simpleName,
                )
                throw BusinessException(ErrorCode.AUTHENTICATION_SERVICE_UNAVAILABLE)
            }

        // 시도 횟수가 제한 미만이라면 로그인 진행 허용
        if (allowed) {
            return AttemptDecision(true, 0)
        }

        // 다음 고정 시간 구간의 시작 시각 계산
        val nextWindow = Instant.ofEpochSecond((windowId + 1) * WINDOW_SECONDS)
        val remaining = Duration.between(now, nextWindow)

        // 남은 시간이 소수점 초 단위라면 올림 처리
        var retryAfterSeconds = remaining.seconds
        if (remaining.nano > 0) {
            retryAfterSeconds++
        }

        return AttemptDecision(false, retryAfterSeconds)
    }

    /**
     * 로그인 요청 제한의 판단 결과.
     *
     * allowed: 이번 로그인 시도를 허용하는지 여부
     * retryAfterSeconds: 다시 시도하기까지 기다릴 시간(초)
     */
    data class AttemptDecision(
        val allowed: Boolean,
        val retryAfterSeconds: Long,
    )
}
