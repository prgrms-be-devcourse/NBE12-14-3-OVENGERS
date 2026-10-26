package com.ovengers.slotkey.access.dto.response

import com.ovengers.slotkey.access.entity.AccessDenyReason
import com.ovengers.slotkey.access.entity.AccessResult
import java.time.LocalDateTime

// TODO: Java 호출부·테스트 전환 완료 후 record 접근자 사용과 JSON 호환을 확인하고 @JvmRecord 제거를 검토한다.
@JvmRecord
data class DoorAccessVerifyResponse(
    val result: AccessResult?,
    val reasonCode: AccessDenyReason?,
    val spaceName: String?,
    val firstCheckIn: Boolean?,
    val attemptedAt: LocalDateTime?,
) {
    // 아직 Java인 출입 서비스·테스트의 getter 호출을 유지한다.
    // TODO: 출입 서비스·테스트의 Kotlin 전환 후 프로퍼티 접근으로 바꾸고 호환용 getter를 제거한다.
    fun getResult(): AccessResult? = result

    fun getReasonCode(): AccessDenyReason? = reasonCode

    fun getSpaceName(): String? = spaceName

    fun getFirstCheckIn(): Boolean? = firstCheckIn

    fun getAttemptedAt(): LocalDateTime? = attemptedAt

    // TODO: Java 정적 호출부·테스트가 모두 전환되면 팩토리의 @JvmStatic 제거를 검토한다.
    companion object {
        // 출입 허용 응답 생성: 기존 Java 정적 호출과 boolean 인자를 유지한다.
        @JvmStatic
        fun allow(
            spaceName: String?,
            firstCheckIn: Boolean,
            attemptedAt: LocalDateTime?,
        ): DoorAccessVerifyResponse =
            DoorAccessVerifyResponse(
                result = AccessResult.ALLOW,
                reasonCode = null,
                spaceName = spaceName,
                firstCheckIn = firstCheckIn,
                attemptedAt = attemptedAt,
            )

        // 출입 거절 응답의 공간명·최초 체크인 여부는 false/빈 문자열 대신 null을 유지한다.
        @JvmStatic
        fun deny(
            reasonCode: AccessDenyReason?,
            attemptedAt: LocalDateTime?,
        ): DoorAccessVerifyResponse =
            DoorAccessVerifyResponse(
                result = AccessResult.DENY,
                reasonCode = reasonCode,
                spaceName = null,
                firstCheckIn = null,
                attemptedAt = attemptedAt,
            )
    }
}
