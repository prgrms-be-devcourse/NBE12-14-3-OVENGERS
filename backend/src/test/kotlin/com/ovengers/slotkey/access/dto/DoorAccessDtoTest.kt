package com.ovengers.slotkey.access.dto

import com.ovengers.slotkey.access.dto.request.DoorAccessVerifyRequest
import com.ovengers.slotkey.access.dto.response.DoorAccessLogResponse
import com.ovengers.slotkey.access.dto.response.DoorAccessTokenResponse
import com.ovengers.slotkey.access.entity.AccessDenyReason
import com.ovengers.slotkey.access.entity.AccessResult
import com.ovengers.slotkey.access.entity.DoorAccessLog
import com.ovengers.slotkey.space.entity.Space
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

class DoorAccessDtoTest {
    @Test
    @DisplayName("출입 검증 요청의 문자열 표현은 원문 토큰을 숨기고 실제 값은 보존한다")
    fun masksRequestTokenInStringRepresentation() {
        val rawToken = "sensitive-door-request-token"
        val request = DoorAccessVerifyRequest(10L, rawToken)
        assertThat(request.toString()).doesNotContain(rawToken).contains("[REDACTED]")
        assertThat(request.token).isEqualTo(rawToken)
    }

    @Test
    @DisplayName("출입 토큰 응답의 문자열 표현은 원문 토큰을 숨기고 실제 값은 보존한다")
    fun masksResponseTokenInStringRepresentation() {
        val rawToken = "sensitive-door-response-token"
        val response = DoorAccessTokenResponse.of(101L, rawToken, LocalDateTime.of(2026, 10, 23, 14, 0))
        assertThat(response.toString()).doesNotContain(rawToken).contains("[REDACTED]")
        assertThat(response.token).isEqualTo(rawToken)
    }

    @Test
    @DisplayName("Java 호환 getter는 큰 공간 ID와 토큰을 보존하고 기본 생성자는 null을 유지한다")
    fun preservesJavaRequestGetterContract() {
        val request = DoorAccessVerifyRequest(2_147_483_648L, "java-compatible-token")
        assertThat(request.getSpaceId()).isEqualTo(2_147_483_648L)
        assertThat(request.getToken()).isEqualTo("java-compatible-token")
        val emptyRequest = DoorAccessVerifyRequest()
        assertThat(emptyRequest.getSpaceId()).isNull()
        assertThat(emptyRequest.getToken()).isNull()
    }

    @Test
    @DisplayName("공간이 있는 출입 로그를 응답으로 변환할 때 공간명과 판정 정보를 보존한다")
    fun mapsAccessLogWithRequestedSpace() {
        val space = mock(Space::class.java)
        `when`(space.name).thenReturn("회의실 A")
        val attemptedAt = LocalDateTime.of(2026, 10, 23, 14, 0)
        val log = DoorAccessLog(null, null, space, AccessResult.ALLOW, null, attemptedAt)
        ReflectionTestUtils.setField(log, "id", 2_147_483_648L)

        val response = DoorAccessLogResponse.from(log)
        assertThat(response.accessLogId).isEqualTo(2_147_483_648L)
        assertThat(response.attemptedAt).isEqualTo(attemptedAt)
        assertThat(response.requestedSpaceName).isEqualTo("회의실 A")
        assertThat(response.result).isEqualTo(AccessResult.ALLOW)
        assertThat(response.reasonCode).isNull()
    }

    @Test
    @DisplayName("공간이 없는 거절 로그도 변환할 수 있고 공간명 null과 거절 사유를 보존한다")
    fun mapsAccessLogWithoutRequestedSpace() {
        val attemptedAt = LocalDateTime.of(2026, 10, 23, 14, 0)
        val log = DoorAccessLog(null, null, null, AccessResult.DENY, AccessDenyReason.TOKEN_NOT_FOUND, attemptedAt)
        ReflectionTestUtils.setField(log, "id", 2L)

        val response = DoorAccessLogResponse.from(log)
        assertThat(response.accessLogId).isEqualTo(2L)
        assertThat(response.attemptedAt).isEqualTo(attemptedAt)
        assertThat(response.requestedSpaceName).isNull()
        assertThat(response.result).isEqualTo(AccessResult.DENY)
        assertThat(response.reasonCode).isEqualTo(AccessDenyReason.TOKEN_NOT_FOUND)
    }
}
