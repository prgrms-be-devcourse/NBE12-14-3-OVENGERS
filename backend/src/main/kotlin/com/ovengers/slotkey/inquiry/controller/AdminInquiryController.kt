package com.ovengers.slotkey.inquiry.controller

import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.common.response.ApiResponse.Companion.success
import com.ovengers.slotkey.global.common.response.PageResponse
import com.ovengers.slotkey.global.common.response.PageResponse.Companion.from
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.global.security.CurrentMember
import com.ovengers.slotkey.inquiry.dto.request.InquiryAnswerRequest
import com.ovengers.slotkey.inquiry.dto.response.InquiryResponse
import com.ovengers.slotkey.inquiry.dto.response.InquiryResponse.Companion.from
import com.ovengers.slotkey.inquiry.entity.InquiryStatus
import com.ovengers.slotkey.inquiry.service.AdminInquiryService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 관리자용 문의 조회/답변 API. SecurityConfig의 "/api/v1/admin/ **" -> hasRole("ADMIN")
 * 매처로 접근이 이미 제한되므로 이 컨트롤러는 별도 역할 검사를 하지 않는다(AdminReservationController와 동일 패턴).
 */
@RestController
@RequestMapping("/api/v1/admin/inquiries")
class AdminInquiryController(
    private val adminInquiryService: AdminInquiryService,
) {
    @GetMapping
    fun getInquiries(
        @RequestParam(name = "status", required = false)
        status: InquiryStatus?,
        @PageableDefault(size = 20)
        pageable: Pageable,
    ): ResponseEntity<ApiResponse<PageResponse<InquiryResponse>>> =
        adminInquiryService
            .getInquiries(status, pageable)
            .map(InquiryResponse::from)
            .let {
                ResponseEntity.ok(
                    ApiResponse.success(PageResponse.from(it)),
                )
            }

    @GetMapping("/{inquiryId}")
    fun getInquiry(
        @PathVariable("inquiryId") inquiryId: Long,
    ): ResponseEntity<ApiResponse<InquiryResponse>> =
        adminInquiryService
            .getInquiry(inquiryId)
            .let {
                ResponseEntity.ok(
                    ApiResponse.success(InquiryResponse.from(it)),
                )
            }

    @PostMapping("/{inquiryId}/answer")
    fun answer(
        @CurrentMember principal: AuthPrincipal,
        @PathVariable("inquiryId") inquiryId: Long,
        @Valid @RequestBody request: InquiryAnswerRequest,
    ): ResponseEntity<ApiResponse<InquiryResponse>> =
        adminInquiryService
            .answer(inquiryId, principal.memberId(), request)
            .let {
                ResponseEntity.ok(
                    ApiResponse.success(InquiryResponse.from(it)),
                )
            }
}
