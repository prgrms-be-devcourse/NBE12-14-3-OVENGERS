package com.ovengers.slotkey.auth.controller

import com.ovengers.slotkey.auth.dto.internal.VerificationCheckResult
import com.ovengers.slotkey.auth.dto.request.VerificationCheckRequest
import com.ovengers.slotkey.auth.dto.request.VerificationIssueRequest
import com.ovengers.slotkey.auth.dto.response.VerificationCheckResponse
import com.ovengers.slotkey.auth.dto.response.VerificationIssueResponse
import com.ovengers.slotkey.auth.service.VerificationChallengeService
import com.ovengers.slotkey.auth.service.VerificationCheckService
import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.global.error.ErrorCode
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth/verification")
class VerificationController(
    private val checkService: VerificationCheckService,
    private val challengeService: VerificationChallengeService,
) {
    @PostMapping("/check")
    fun check(
        @Valid @RequestBody request: VerificationCheckRequest,
    ): ResponseEntity<ApiResponse<VerificationCheckResponse>> {
        val result =
            checkService.verify(
                request.email,
                request.purpose,
                request.challengeId,
                request.code,
            )

        return when (result.status) {
            VerificationCheckResult.Status.WRONG_CODE ->
                throw BusinessException(ErrorCode.VERIFICATION_CODE_MISMATCH)
            VerificationCheckResult.Status.INVALID_CHALLENGE ->
                throw BusinessException(ErrorCode.VERIFICATION_CHALLENGE_INVALID)
            VerificationCheckResult.Status.TEMPORARILY_BLOCKED ->
                throw BusinessException(ErrorCode.VERIFICATION_TEMPORARILY_BLOCKED)
            VerificationCheckResult.Status.ADMIN_LOCKED ->
                throw BusinessException(ErrorCode.VERIFICATION_ADMIN_LOCKED)
            VerificationCheckResult.Status.SUCCESS ->
                ResponseEntity
                    .ok()
                    .cacheControl(CacheControl.noStore())
                    .body(
                        ApiResponse.success(
                            VerificationCheckResponse(
                                requireNotNull(result.grant),
                                requireNotNull(result.expiresAt),
                            ),
                        ),
                    )
        }
    }

    @PostMapping("/issue")
    fun issue(
        @Valid @RequestBody request: VerificationIssueRequest,
    ): ResponseEntity<ApiResponse<VerificationIssueResponse>> {
        val result = challengeService.issue(request.email, request.purpose)
        return ResponseEntity.ok(
            ApiResponse.success(
                VerificationIssueResponse(
                    result.challengeId,
                    result.expiresAt,
                    result.resendAvailableAt,
                ),
            ),
        )
    }
}
