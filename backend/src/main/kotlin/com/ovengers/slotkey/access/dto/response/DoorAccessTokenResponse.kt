package com.ovengers.slotkey.access.dto.response

import java.time.LocalDateTime

// TODO: Java 호출부·테스트 전환 완료 후 record 접근자 사용과 JSON 호환을 확인하고 @JvmRecord 제거를 검토한다.
@JvmRecord
data class DoorAccessTokenResponse(
    val reservationId: Long?,
    val token: String?,
    val issuedAt: LocalDateTime?,
) {
    // 아직 Java인 출입 서비스·테스트의 getter 호출을 유지한다.
    // TODO: 출입 서비스·테스트의 Kotlin 전환 후 프로퍼티 접근으로 바꾸고 호환용 getter를 제거한다.
    fun getReservationId(): Long? = reservationId

    fun getToken(): String? = token

    fun getIssuedAt(): LocalDateTime? = issuedAt

    // data class의 자동 문자열 표현에 원문 출입 토큰이 노출되지 않게 한다.
    override fun toString(): String = "DoorAccessTokenResponse(reservationId=$reservationId, token=[REDACTED], issuedAt=$issuedAt)"

    // TODO: Java 정적 호출부·테스트가 모두 전환되면 팩토리의 @JvmStatic 제거를 검토한다.
    companion object {
        // 출입 토큰 발급 응답 생성: 기존 Java의 DoorAccessTokenResponse.of(...) 호출 유지
        @JvmStatic
        fun of(
            reservationId: Long?,
            token: String?,
            issuedAt: LocalDateTime?,
        ): DoorAccessTokenResponse =
            DoorAccessTokenResponse(
                reservationId = reservationId,
                token = token,
                issuedAt = issuedAt,
            )
    }
}
