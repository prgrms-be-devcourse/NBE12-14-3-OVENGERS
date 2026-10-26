package com.ovengers.slotkey.access.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

// TODO: Java 호출부·테스트 전환 완료 후 record 접근자 사용과 JSON 호환을 확인하고 @JvmRecord 제거를 검토한다.
@JvmRecord
data class DoorAccessVerifyRequest(
    @field:NotNull(message = "오피스 ID는 필수 입니다.")
    val spaceId: Long?,
    @field:NotBlank(message = "출입 토큰은 필수입니다.")
    val token: String?,
) {
    // 기존 Java 기본 생성자와 getter 호출을 전환 기간 동안 유지한다.
    // TODO: Java 호출부·테스트 전환 후 기본 생성자와 getter 사용처가 없고 요청 바인딩이 유지되면 제거한다.
    constructor() : this(null, null)

    fun getSpaceId(): Long? = spaceId

    fun getToken(): String? = token

    // data class의 자동 문자열 표현에 원문 출입 토큰이 노출되지 않게 한다.
    override fun toString(): String = "DoorAccessVerifyRequest(spaceId=$spaceId, token=[REDACTED])"
}
