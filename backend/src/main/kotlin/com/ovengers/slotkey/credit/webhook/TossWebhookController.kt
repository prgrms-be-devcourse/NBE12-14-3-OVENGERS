package com.ovengers.slotkey.credit.webhook

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "PG 웹훅", description = "토스페이먼츠 웹훅 수신")
@RestController
@RequestMapping("/api/v1/webhooks/tosspayments")
class TossWebhookController(
    private val receiver: TossWebhookReceiver,
) {
    @Operation(
        summary = "토스페이먼츠 웹훅 수신",
        description = "PAYMENT_STATUS_CHANGED를 즉시 기록하고 200을 반환합니다. 충전 확정은 별도 처리에서 PG 조회로만 합니다.",
    )
    @PostMapping
    fun receive(
        @RequestHeader(value = "tosspayments-webhook-transmission-id", required = false) transmissionId: String?,
        @RequestHeader(value = "tosspayments-webhook-transmission-retried-count", required = false) retriedCount: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<Void> {
        receiver.receive(transmissionId, retriedCount, body)
        return ResponseEntity.ok().build()
    }
}
