package com.ovengers.slotkey.waitlist.controller;

import com.ovengers.slotkey.global.common.response.ApiResponse;
import com.ovengers.slotkey.global.security.AuthPrincipal;
import com.ovengers.slotkey.global.security.CurrentMember;
import com.ovengers.slotkey.waitlist.dto.request.WaitlistApplyRequest;
import com.ovengers.slotkey.waitlist.dto.response.WaitlistEntryResponse;
import com.ovengers.slotkey.waitlist.service.WaitlistService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 예약 대기 API(1단계 — 신청·취소·내 목록).
 * SecurityConfig의 anyRequest().authenticated()가 인증을 강제하므로, 소유권 검사는 서비스가 한다
 * (타인의 대기 취소는 FORBIDDEN_NOT_OWNER).
 *
 * 신규 코드이므로 Lombok을 쓰지 않는다(AGENTS.md).
 */
@RestController
@RequestMapping("/api/v1/waitlist")
public class WaitlistController {

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WaitlistEntryResponse>> apply(
            @CurrentMember AuthPrincipal principal,
            @Valid @RequestBody WaitlistApplyRequest request) {
        WaitlistEntryResponse response = waitlistService.apply(
                principal.memberId(),
                request.spaceId(),
                request.startTime(),
                request.endTime());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @DeleteMapping("/{waitlistEntryId}")
    public ResponseEntity<Void> cancel(
            @CurrentMember AuthPrincipal principal,
            @PathVariable Long waitlistEntryId) {
        waitlistService.cancel(principal.memberId(), waitlistEntryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<WaitlistEntryResponse>>> getMyEntries(
            @CurrentMember AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(waitlistService.getMyEntries(principal.memberId())));
    }
}
