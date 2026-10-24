package com.ovengers.slotkey.notification.controller

import com.ovengers.slotkey.global.common.response.ApiResponse
import com.ovengers.slotkey.global.common.response.PageResponse
import com.ovengers.slotkey.global.security.AuthPrincipal
import com.ovengers.slotkey.global.security.CurrentMember
import com.ovengers.slotkey.notification.dto.response.NotificationResponse
import com.ovengers.slotkey.notification.dto.response.ReadAllResponse
import com.ovengers.slotkey.notification.dto.response.UnreadCountResponse
import com.ovengers.slotkey.notification.service.NotificationQueryService
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(
    private val notificationQueryService: NotificationQueryService,
) {
    @GetMapping
    fun getMyNotifications(
        @CurrentMember principal: AuthPrincipal,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> =
        ResponseEntity.ok(
            ApiResponse.success(
                PageResponse.from(notificationQueryService.getMyNotifications(principal.memberId(), pageable)),
            ),
        )

    @GetMapping("/unread-count")
    fun getUnreadCount(
        @CurrentMember principal: AuthPrincipal,
    ): ResponseEntity<ApiResponse<UnreadCountResponse>> =
        ResponseEntity.ok(
            ApiResponse.success(UnreadCountResponse(notificationQueryService.countUnread(principal.memberId()))),
        )

    @PostMapping("/read-all")
    fun readAll(
        @CurrentMember principal: AuthPrincipal,
        @RequestParam("upToId") upToId: Long,
    ): ResponseEntity<ApiResponse<ReadAllResponse>> =
        ResponseEntity.ok(
            ApiResponse.success(ReadAllResponse(notificationQueryService.readAll(principal.memberId(), upToId))),
        )
}
