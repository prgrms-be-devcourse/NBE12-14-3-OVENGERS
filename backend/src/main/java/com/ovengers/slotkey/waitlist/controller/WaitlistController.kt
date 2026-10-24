package com.ovengers.slotkey.waitlist.controller

import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.global.security.CurrentMember
import com.ovengers.slotkey.waitlist.dto.request.WaitlistApplyRequest
import com.ovengers.slotkey.waitlist.dto.response.WaitlistEntryResponse
import com.ovengers.slotkey.waitlist.service.WaitlistService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/waitlist")
class WaitlistController(
    private val waitlistService: WaitlistService,
) {
    @PostMapping
    fun apply(
        @CurrentMember principal: AuthPrincipal,
        @Valid @RequestBody request: WaitlistApplyRequest,
    ): ResponseEntity<ApiResponse<WaitlistEntryResponse>> {
        val response =
            waitlistService.apply(
                principal.memberId(),
                requireNotNull(request.spaceId),
                requireNotNull(request.startTime),
                requireNotNull(request.endTime),
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response))
    }

    @DeleteMapping("/{waitlistEntryId}")
    fun cancel(
        @CurrentMember principal: AuthPrincipal,
        @PathVariable waitlistEntryId: Long,
    ): ResponseEntity<Void> {
        waitlistService.cancel(principal.memberId(), waitlistEntryId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    fun getMyEntries(
        @CurrentMember principal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<List<WaitlistEntryResponse>>> =
        ResponseEntity.ok(ApiResponse.success(waitlistService.getMyEntries(principal.memberId())))
}
