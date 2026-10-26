package com.ovengers.slotkey.member.controller

import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.common.response.PageResponse
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.member.authorization.MemberAuthorizationService
import com.ovengers.slotkey.member.dto.request.MemberCreditGrantRequest
import com.ovengers.slotkey.member.dto.request.MemberStatusChangeRequest
import com.ovengers.slotkey.member.dto.response.AdminMemberCreditTransactionResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberDetailResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberReservationResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberStatusHistoryResponse
import com.ovengers.slotkey.member.dto.response.MemberStatusChangeResponse
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.member.service.AdminMemberService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.responses.ApiResponse as OpenApiResponse

@ApiResponses(
    value = [
        OpenApiResponse(
            responseCode = "401",
            description = "AUTHENTICATION_REQUIRED / INVALID_ACCESS_TOKEN: 인증 정보가 없거나 토큰이 유효하지 않음",
            content = [Content()],
        ),
        OpenApiResponse(
            responseCode = "403",
            description = "ACCESS_DENIED / FORBIDDEN_ROLE: 관리자 권한이 없음",
            content = [Content()],
        ),
    ],
)
@Tag(name = "관리자 - 회원", description = "회원 조회, 정지·복구 및 크레딧 지급 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/members")
class AdminMemberController(
    private val adminMemberService: AdminMemberService,
    private val memberAuthorizationService: MemberAuthorizationService,
) {
    @Operation(
        summary = "회원 목록 조회",
        description = """
            관리자 권한으로 회원 목록을 조회합니다.
            status로 회원 상태를 필터링하고, keyword로 검색할 수 있습니다.
            page는 0부터 시작하며, 기본 페이지 크기는 20입니다.
        """,
    )
    @ApiResponses(value = [OpenApiResponse(responseCode = "200", description = "회원 목록 조회 성공", useReturnTypeSchema = true)])
    @GetMapping
    fun getMembers(
        @RequestParam(name = "status", required = false) status: MemberStatus?,
        @RequestParam(name = "keyword", required = false) keyword: String?,
        @ParameterObject @PageableDefault(size = 20) pageable: Pageable,
        @AuthenticationPrincipal authPrincipal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<PageResponse<AdminMemberResponse>>> {
        memberAuthorizationService.validateCanManageMember(authPrincipal)
        val response = PageResponse.from(adminMemberService.getMembers(status, keyword, pageable))
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(summary = "관리자 회원 상세 조회", description = "특정 회원의 기본 정보, 전체 예약 건수, 정지 기록 건수를 조회합니다.")
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "회원 상세 조회 성공", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND: 회원이 존재하지 않음", content = [Content()]),
        ],
    )
    @GetMapping("/{memberId}")
    fun getMemberDetail(
        @PathVariable("memberId") memberId: Long,
        @AuthenticationPrincipal authPrincipal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<AdminMemberDetailResponse>> {
        memberAuthorizationService.validateCanManageMember(authPrincipal)
        return ResponseEntity.ok(ApiResponse.success(adminMemberService.getMemberDetail(memberId)))
    }

    @Operation(summary = "회원 크레딧 거래 내역 조회", description = "관리자가 특정 회원의 크레딧 충전, 사용, 환급 등 거래 내역을 최신순으로 조회합니다.")
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "크레딧 거래 내역 조회 성공", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND: 회원이 존재하지 않음", content = [Content()]),
        ],
    )
    @GetMapping("/{memberId}/credit-transactions")
    fun getMemberCreditTransactions(
        @PathVariable("memberId") memberId: Long,
        @ParameterObject @PageableDefault(size = 20) pageable: Pageable,
        @AuthenticationPrincipal authPrincipal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<PageResponse<AdminMemberCreditTransactionResponse>>> {
        memberAuthorizationService.validateCanManageMember(authPrincipal)
        val response = PageResponse.from(adminMemberService.getMemberCreditTransactions(memberId, pageable))
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(
        summary = "관리자 회원별 예약 내역 조회",
        description = """
            특정 회원의 전체 예약 내역을 조회합니다.
            취소 및 만료된 예약도 포함합니다.
            예약 ID 내림차순으로 정렬하며,
            page는 0부터 시작하고 기본 페이지 크기는 20입니다.
        """,
    )
    @GetMapping("/{memberId}/reservations")
    fun getMemberReservations(
        @PathVariable("memberId") memberId: Long,
        @ParameterObject @PageableDefault(size = 20) pageable: Pageable,
        @AuthenticationPrincipal authPrincipal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<PageResponse<AdminMemberReservationResponse>>> {
        memberAuthorizationService.validateCanManageMember(authPrincipal)
        val response = PageResponse.from(adminMemberService.getMemberReservations(memberId, pageable))
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(summary = "관리자 회원 상태 변경 이력 조회", description = "특정 회원의 정지, 복구 및 인증 잠금 기록을 최신순으로 조회합니다.")
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "회원 상태 변경 이력 조회 성공", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND: 회원이 존재하지 않음", content = [Content()]),
        ],
    )
    @GetMapping("/{memberId}/status-history")
    fun getMemberStatusHistory(
        @PathVariable("memberId") memberId: Long,
        @ParameterObject @PageableDefault(size = 20) pageable: Pageable,
        @AuthenticationPrincipal authPrincipal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<PageResponse<AdminMemberStatusHistoryResponse>>> {
        memberAuthorizationService.validateCanManageMember(authPrincipal)
        val response = PageResponse.from(adminMemberService.getMemberStatusHistory(memberId, pageable))
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(summary = "회원 정지", description = "관리자 권한으로 대상 회원을 정지합니다. 요청 본문에 정지 사유를 입력해야 합니다.")
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "회원 상태 변경 성공", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 변경 사유 누락 또는 길이 제한 위반", content = [Content()]),
            OpenApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND: 대상 회원이 존재하지 않음", content = [Content()]),
            OpenApiResponse(responseCode = "422", description = "TARGET_IS_ADMIN: 관리자 계정은 정지하거나 복구할 수 없음", content = [Content()]),
        ],
    )
    @PatchMapping("/{memberId}/suspend")
    fun suspend(
        @PathVariable("memberId") memberId: Long,
        @Valid @RequestBody request: MemberStatusChangeRequest,
        @AuthenticationPrincipal authPrincipal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<MemberStatusChangeResponse>> {
        memberAuthorizationService.validateCanManageMember(authPrincipal)
        val response = adminMemberService.suspend(memberId, request.reason, authPrincipal.memberId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(summary = "회원 복구", description = "관리자 권한으로 대상 회원의 상태를 복구합니다. 요청 본문에 복구 사유를 입력해야 합니다.")
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "회원 상태 변경 성공", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 변경 사유 누락 또는 길이 제한 위반", content = [Content()]),
            OpenApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND: 대상 회원이 존재하지 않음", content = [Content()]),
            OpenApiResponse(responseCode = "422", description = "TARGET_IS_ADMIN: 관리자 계정은 정지하거나 복구할 수 없음", content = [Content()]),
        ],
    )
    @PatchMapping("/{memberId}/restore")
    fun restore(
        @PathVariable("memberId") memberId: Long,
        @Valid @RequestBody request: MemberStatusChangeRequest,
        @AuthenticationPrincipal authPrincipal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<MemberStatusChangeResponse>> {
        memberAuthorizationService.validateCanManageMember(authPrincipal)
        val response = adminMemberService.restore(memberId, request.reason, authPrincipal.memberId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @Operation(
        summary = "회원 크레딧 지급",
        description = "관리자 권한으로 대상 회원에게 크레딧을 지급합니다. 요청 본문에 지급 금액과 사유를 입력해야 합니다.",
    )
    @ApiResponses(
        value = [
            OpenApiResponse(responseCode = "200", description = "크레딧 지급 성공. 지급 후 회원 정보 반환", useReturnTypeSchema = true),
            OpenApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 지급 금액 또는 사유가 입력 조건에 맞지 않음", content = [Content()]),
            OpenApiResponse(responseCode = "404", description = "MEMBER_NOT_FOUND: 대상 회원이 존재하지 않음", content = [Content()]),
            OpenApiResponse(responseCode = "422", description = "SELF_GRANT_NOT_ALLOWED: 자기 자신에게 크레딧을 지급할 수 없음", content = [Content()]),
        ],
    )
    @PostMapping("/{memberId}/credits")
    fun grantCredit(
        @PathVariable("memberId") memberId: Long,
        @Valid @RequestBody request: MemberCreditGrantRequest,
        @AuthenticationPrincipal authPrincipal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<AdminMemberResponse>> {
        memberAuthorizationService.validateCanManageMember(authPrincipal)
        val response =
            adminMemberService.grantCredit(
                memberId,
                requireNotNull(request.amount),
                request.reason,
                authPrincipal.memberId(),
            )
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}
