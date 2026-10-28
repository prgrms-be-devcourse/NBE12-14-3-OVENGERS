package com.ovengers.slotkey.credit.controller

import com.ovengers.slotkey.credit.dto.response.CreditGrantHistoryResponse
import com.ovengers.slotkey.credit.service.CreditGrantService
import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.common.response.PageResponse
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.global.security.CurrentMember
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "크레딧 충전", description = "PG 크레딧 충전 주문 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/credits/grants")
class CreditGrantController(
    private val creditGrantService: CreditGrantService,
) {
    @Operation(summary = "내 관리자 지급 내역 조회", description = "인증 회원 본인이 관리자에게 지급받은 크레딧(ADMIN_GRANT) 내역을 최신순으로 조회합니다.")
    @GetMapping
    fun list(
        @Parameter(hidden = true) @CurrentMember principal: AuthPrincipal,
        @PageableDefault(size = 10) pageable: Pageable,
    ): ApiResponse<PageResponse<CreditGrantHistoryResponse>> =
        ApiResponse.success(PageResponse.from(creditGrantService.getMyAdminGrants(principal.memberId, pageable)))
}
