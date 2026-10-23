package com.ovengers.slotkey.credit.controller;

import com.ovengers.slotkey.credit.dto.request.ChargeOrderCreateRequest;
import com.ovengers.slotkey.credit.dto.request.ChargeOrderConfirmRequest;
import com.ovengers.slotkey.credit.dto.response.ChargeOrderConfirmResponse;
import com.ovengers.slotkey.credit.dto.response.ChargeOrderResponse;
import com.ovengers.slotkey.credit.entity.ChargeOrderStatus;
import com.ovengers.slotkey.credit.service.ChargeOrderConfirmService;
import com.ovengers.slotkey.credit.service.ChargeOrderService;
import com.ovengers.slotkey.global.common.response.ApiResponse;
import com.ovengers.slotkey.global.common.response.PageResponse;
import com.ovengers.slotkey.credit.dto.response.ChargeOrderHistoryResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import com.ovengers.slotkey.global.security.AuthPrincipal;
import com.ovengers.slotkey.global.security.CurrentMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "크레딧 충전", description = "PG 크레딧 충전 주문 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/credits/charge-orders")
public class ChargeOrderController {

    private final ChargeOrderService chargeOrderService;
    private final ChargeOrderConfirmService chargeOrderConfirmService;

    public ChargeOrderController(
            ChargeOrderService chargeOrderService,
            ChargeOrderConfirmService chargeOrderConfirmService
    ) {
        this.chargeOrderService = chargeOrderService;
        this.chargeOrderConfirmService = chargeOrderConfirmService;
    }

    @Operation(
            summary = "충전 주문 생성",
            description = "허용된 충전 금액을 검증하고 PENDING 상태의 충전 주문을 생성합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "충전 주문 생성 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "VALIDATION_FAILED: 충전 금액이 허용 범위를 벗어나거나 100원 단위가 아님",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 정보가 없거나 액세스 토큰이 유효하지 않음",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "ACCOUNT_INACTIVE: ACTIVE 상태가 아닌 회원",
                    content = @Content
            )
    })
    @PostMapping
    public ResponseEntity<ApiResponse<ChargeOrderResponse>> create(
            @Parameter(hidden = true)
            @CurrentMember AuthPrincipal principal,
            @Valid @RequestBody ChargeOrderCreateRequest request
    ) {
        ChargeOrderResponse response = chargeOrderService.create(
                principal.memberId(),
                request.amount()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @Operation(
            summary = "내 충전 내역 조회",
            description = "인증 회원 본인의 PG 충전 내역(PAID·CONFIRMING·FAILED)을 최신순으로 조회합니다."
    )
    @GetMapping
    public ApiResponse<PageResponse<ChargeOrderHistoryResponse>> list(
            @Parameter(hidden = true)
            @CurrentMember AuthPrincipal principal,
            @PageableDefault(size = 10) Pageable pageable
    ) {
        return ApiResponse.success(PageResponse.from(
                chargeOrderService.getMyHistory(principal.memberId(), pageable)));
    }

    @Operation(
            summary = "충전 주문 상태 조회",
            description = "인증 회원 본인의 충전 주문 현재 상태를 조회합니다."
    )
    @GetMapping("/{orderId}")
    public ApiResponse<ChargeOrderResponse> get(
            @Parameter(hidden = true)
            @CurrentMember AuthPrincipal principal,
            @PathVariable String orderId
    ) {
        return ApiResponse.success(chargeOrderService.get(principal.memberId(), orderId));
    }

    @Operation(
            summary = "충전 주문 승인",
            description = "저장된 주문 금액을 검증하고 PG 승인 후 크레딧을 충전합니다."
    )
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<ChargeOrderConfirmResponse>> confirm(
            @Parameter(hidden = true)
            @CurrentMember AuthPrincipal principal,
            @Valid @RequestBody ChargeOrderConfirmRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey
    ) {
        ChargeOrderConfirmResponse response = chargeOrderConfirmService.confirm(
                principal.memberId(),
                request.paymentKey(),
                request.orderId(),
                request.amount(),
                idempotencyKey
        );
        HttpStatus status = response.status() == ChargeOrderStatus.CONFIRMING
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(response));
    }
}
