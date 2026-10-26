package com.ovengers.slotkey.member.controller

import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.member.dto.request.MemberUpdateRequest
import com.ovengers.slotkey.member.dto.response.MemberResponse
import com.ovengers.slotkey.member.service.MemberService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as OpenApiResponse

@Tag(name = "회원", description = "회원 정보 조회 API")
@RestController
@RequestMapping("/api/v1/members")
class MemberController(
    private val memberService: MemberService,
) {
    @Operation(summary = "내 정보 조회", description = "로그인한 회원의 이메일, 닉네임, 역할, 크레딧 잔액 등을 조회합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "내 정보 조회 성공", useReturnTypeSchema = true),
            OpenApiResponse(
                responseCode = "401",
                description = """
                    AUTHENTICATION_REQUIRED: 인증 정보가 없거나 회원이 존재하지 않음.
                    INVALID_ACCESS_TOKEN: 액세스 토큰이 만료되었거나 유효하지 않음.
                """,
                content = [Content()],
            ),
        ],
    )
    @GetMapping("/me")
    fun getMyInfo(
        @AuthenticationPrincipal principal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<MemberResponse>> =
        ResponseEntity.ok(ApiResponse.success(MemberResponse(memberService.getMyInfo(principal.memberId))))

    @Operation(summary = "내 닉네임 수정", description = "로그인한 회원의 닉네임을 수정합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/me")
    fun updateMyInfo(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: MemberUpdateRequest,
    ): ResponseEntity<ApiResponse<MemberResponse>> =
        ResponseEntity.ok(
            ApiResponse.success(memberService.updateNickname(principal.memberId, request.nickname())),
        )
}
