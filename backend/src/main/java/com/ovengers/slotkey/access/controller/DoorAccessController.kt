package com.ovengers.slotkey.access.controller

import com.ovengers.slotkey.access.dto.request.DoorAccessVerifyRequest
import com.ovengers.slotkey.access.dto.response.DoorAccessLogResponse
import com.ovengers.slotkey.access.dto.response.DoorAccessTokenResponse
import com.ovengers.slotkey.access.dto.response.DoorAccessVerifyResponse
import com.ovengers.slotkey.access.service.DoorAccessLogService
import com.ovengers.slotkey.access.service.DoorAccessTokenService
import com.ovengers.slotkey.access.service.DoorAccessVerificationService
import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.security.AuthPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDateTime
import io.swagger.v3.oas.annotations.responses.ApiResponse as OpenApiResponse

@Tag(name = "출입", description = "예약 공간의 출입 토큰 발급·폐기, 출입 확인 및 기록 조회 API")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses(
    value = [
        OpenApiResponse(
            responseCode = "401",
            description = "인증 정보가 없거나 로그인용 액세스 토큰이 유효하지 않음",
            content = [Content()],
        ),
    ],
)
@RestController
@RequestMapping("/api/v1")
class DoorAccessController(
    private val doorAccessTokenService: DoorAccessTokenService,
    private val doorAccessLogService: DoorAccessLogService,
    private val doorAccessVerificationService: DoorAccessVerificationService,
    private val clock: Clock,
) {
    @Operation(
        summary = "출입 토큰 발급",
        description = """
            본인 예약에 사용할 공간 출입 토큰을 발급합니다.
            로그인용 JWT와 별개의 토큰입니다.
            재발급 시 기존 활성 출입 토큰은 폐기됩니다.
        """,
    )
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "201", description = "출입 토큰 발급 성공", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "403", description = "FORBIDDEN_NOT_OWNER: 본인 예약이 아님", content = [Content()]),
            OpenApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND: 예약이 존재하지 않음", content = [Content()]),
            OpenApiResponse(responseCode = "409", description = "RESERVATION_STATE_CONFLICT: 출입 토큰을 발급할 수 없는 예약 상태", content = [Content()]),
        ],
    )
    @PostMapping("/reservations/{reservationId}/door-token")
    fun issueToken(
        @PathVariable reservationId: Long,
        @Parameter(hidden = true) @AuthenticationPrincipal authPrincipal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<DoorAccessTokenResponse>> {
        val response = doorAccessTokenService.issue(authPrincipal.memberId(), reservationId)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response))
    }

    @Operation(
        summary = "출입 토큰 폐기",
        description = """
            본인 예약에 발급된 출입 토큰을 폐기합니다.
            경로의 access-token은 공간 출입 토큰을 의미하며, 로그인용 JWT는 폐기하지 않습니다.
        """,
    )
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "출입 토큰 폐기 처리 완료", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "403", description = "FORBIDDEN_NOT_OWNER: 본인 예약이 아님", content = [Content()]),
            OpenApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND: 예약이 존재하지 않음", content = [Content()]),
        ],
    )
    @PatchMapping("/reservations/{reservationId}/access-token/revoke")
    fun revokeToken(
        @PathVariable reservationId: Long,
        @Parameter(hidden = true) @AuthenticationPrincipal authPrincipal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<Void>> {
        doorAccessTokenService.revokeByReservationId(
            authPrincipal.memberId(),
            reservationId,
            LocalDateTime.now(clock),
            "사용자 요청",
        )
        return ResponseEntity.ok(ApiResponse.success())
    }

    @Operation(
        summary = "예약별 출입 기록 조회",
        description = "본인 예약의 출입 시도 시각, 요청 공간, 허용·거절 결과 및 거절 사유를 조회합니다.",
    )
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "출입 기록 조회 성공", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "403", description = "FORBIDDEN_NOT_OWNER: 본인 예약이 아님", content = [Content()]),
            OpenApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND: 예약이 존재하지 않음", content = [Content()]),
        ],
    )
    @GetMapping("/reservations/{reservationId}/access-logs")
    fun findAccessLogs(
        @PathVariable reservationId: Long,
        @Parameter(hidden = true) @AuthenticationPrincipal authPrincipal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<List<DoorAccessLogResponse>>> =
        ResponseEntity.ok(
            ApiResponse.success(
                doorAccessLogService.findResponsesByReservationId(authPrincipal.memberId(), reservationId),
            ),
        )

    @Operation(
        summary = "공간 출입 확인",
        description = """
            로그인한 회원이 제출한 공간 ID와 출입 토큰으로 출입 가능 여부를 확인합니다.
            Authorization 헤더에는 로그인용 JWT를, 요청 본문의 token에는 출입 토큰을 전달합니다.
            HTTP 200이어도 출입이 거절될 수 있습니다.
            data.result의 ALLOW 또는 DENY를 확인하고, 거절 시 data.reasonCode를 확인하세요.
            최초 입실 허용 시 예약이 이용 중 상태로 변경될 수 있습니다.
        """,
    )
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "출입 판정 완료. 실제 허용 여부는 data.result 확인", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 공간 ID 또는 출입 토큰 누락", content = [Content()]),
        ],
    )
    @PostMapping("/door-access/verify")
    fun verify(
        @Parameter(hidden = true) @AuthenticationPrincipal authPrincipal: AuthPrincipal,
        @Valid @RequestBody request: DoorAccessVerifyRequest,
    ): ResponseEntity<ApiResponse<DoorAccessVerifyResponse>> =
        ResponseEntity.ok(
            ApiResponse.success(doorAccessVerificationService.verify(authPrincipal.memberId(), request)),
        )
}
