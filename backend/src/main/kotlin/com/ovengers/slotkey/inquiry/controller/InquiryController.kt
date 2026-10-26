
package com.ovengers.slotkey.inquiry.controller

import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.common.response.PageResponse
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.global.security.CurrentMember
import com.ovengers.slotkey.inquiry.dto.request.InquiryCreateRequest
import com.ovengers.slotkey.inquiry.dto.request.InquiryUpdateRequest
import com.ovengers.slotkey.inquiry.dto.response.InquiryResponse
import com.ovengers.slotkey.inquiry.service.InquiryService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/inquiries")
class InquiryController(
    private val inquiryService: InquiryService,
) {
    @PostMapping
    fun create(
        @CurrentMember principal: AuthPrincipal,
        @Valid @RequestBody request: InquiryCreateRequest,
    ): ResponseEntity<ApiResponse<InquiryResponse>> =
        inquiryService
            .create(principal.memberId!!, request)
            .let {
                ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(ApiResponse.success(InquiryResponse.from(it)))
            }

    @GetMapping
    fun getMyInquiries(
        @CurrentMember principal: AuthPrincipal,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<ApiResponse<PageResponse<InquiryResponse>>> =
        inquiryService
            .getMyInquiries(principal.memberId!!, pageable)
            .map(InquiryResponse::from)
            .let {
                ResponseEntity.ok(
                    ApiResponse.success(PageResponse.from(it)),
                )
            }

    @GetMapping("/{inquiryId}")
    fun getMyInquiry(
        @CurrentMember principal: AuthPrincipal,
        @PathVariable inquiryId: Long,
    ): ResponseEntity<ApiResponse<InquiryResponse>> =
        inquiryService
            .getMyInquiry(principal.memberId!!, inquiryId)
            .let {
                ResponseEntity.ok(
                    ApiResponse.success(InquiryResponse.from(it)),
                )
            }

    @PatchMapping("/{inquiryId}")
    fun update(
        @CurrentMember principal: AuthPrincipal,
        @PathVariable inquiryId: Long,
        @Valid @RequestBody request: InquiryUpdateRequest,
    ): ResponseEntity<ApiResponse<InquiryResponse>> =
        inquiryService
            .update(principal.memberId!!, inquiryId, request)
            .let {
                ResponseEntity.ok(
                    ApiResponse.success(InquiryResponse.from(it)),
                )
            }
}
