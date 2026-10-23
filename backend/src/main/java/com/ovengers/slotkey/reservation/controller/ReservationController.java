package com.ovengers.slotkey.reservation.controller;

import com.ovengers.slotkey.global.common.response.ApiResponse;
import com.ovengers.slotkey.global.common.response.PageResponse;
import com.ovengers.slotkey.global.security.AuthPrincipal;
import com.ovengers.slotkey.global.security.CurrentMember;
import com.ovengers.slotkey.reservation.dto.request.ReservationCreateRequest;
import com.ovengers.slotkey.reservation.dto.request.ReservationExtendRequest;
import com.ovengers.slotkey.reservation.dto.request.ReservationPayRequest;
import com.ovengers.slotkey.reservation.dto.request.ReservationSearchCondition;
import com.ovengers.slotkey.reservation.dto.response.ReservationDetailResponse;
import com.ovengers.slotkey.reservation.dto.response.ReservationResponse;
import com.ovengers.slotkey.reservation.service.ReservationCancelService;
import com.ovengers.slotkey.reservation.service.ReservationCheckOutService;
import com.ovengers.slotkey.reservation.service.ReservationExtendService;
import com.ovengers.slotkey.reservation.service.ReservationHoldService;
import com.ovengers.slotkey.reservation.service.ReservationPaymentConfirmService;
import com.ovengers.slotkey.reservation.service.ReservationQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ovengers.slotkey.reservation.dto.response.ReservationListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
/**
 * 본인 예약 API. SecurityConfig에서 이 경로는 anyRequest().authenticated()로 이미
 * 인증을 강제하므로, 여기서는 소유권 검사만 신경 쓰면 된다 — 각 서비스가
 * FORBIDDEN_NOT_OWNER(403)로 이미 처리하고 있어 컨트롤러는 memberId만 그대로 넘긴다.
 */
@Tag(name = "예약", description = "본인 예약 생성, 결제, 조회 및 이용 관리 API")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증 정보가 없거나 액세스 토큰이 유효하지 않음",
                content = @Content
        )
})
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationHoldService reservationHoldService;
    private final ReservationPaymentConfirmService reservationPaymentConfirmService;
    private final ReservationCancelService reservationCancelService;
    private final ReservationExtendService reservationExtendService;
    private final ReservationCheckOutService reservationCheckOutService;
    private final ReservationQueryService reservationQueryService;


    /** 예약 HOLD 생성 (결제 전 슬롯 선점, 10분간 유효). */
    @Operation(
            summary = "예약 생성 및 슬롯 임시 확보",
            description = """
                선택한 공간과 시간대의 슬롯을 임시 확보하고 HELD 상태의 예약을 생성합니다.
                이 단계에서는 크레딧이 차감되지 않습니다.
                임시 확보는 10분간 유효하며, 응답의 holdExpiresAt 이전에 결제해야 합니다.
                응답의 spaceVersion은 결제 요청에 전달합니다.
                """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "예약 생성 및 슬롯 임시 확보 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "VALIDATION_FAILED / INVALID_RESERVATION_TIME: 입력값 또는 예약 시간 조건 오류",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SPACE_NOT_FOUND: 공간이 존재하지 않음",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "RESERVATION_SLOT_CONFLICT: 요청한 시간대에 이미 확보된 슬롯이 있음",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "SPACE_INACTIVE: 예약할 수 없는 공간",
                    content = @Content
            )
    })
    @PostMapping
    public ResponseEntity<ApiResponse<ReservationResponse>> hold(
            @Parameter(hidden = true)
            @CurrentMember AuthPrincipal principal,
            @Valid @RequestBody ReservationCreateRequest request
    ) {
        ReservationResponse response = reservationHoldService.createHold(
                principal.memberId(),
                request.spaceId(),
                request.toStartDateTime(),
                request.toEndDateTime()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }


    /** 결제 확인 (HOLD -> CONFIRMED). Idempotency-Key 헤더 필수. */
    @Operation(
            summary = "크레딧 결제 및 예약 확정",
            description = """
                본인의 유효한 HELD 예약에 대해 크레딧을 차감하고 CONFIRMED 상태로 확정합니다.
                예약 생성 응답의 spaceVersion을 요청 본문에 전달합니다.
                Idempotency-Key 헤더가 필요합니다.
                같은 결제 요청을 재시도할 때는 동일한 키를 사용하고, 다른 결제 요청에는 새로운 키를 사용합니다.
                """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "결제 처리 결과 반환",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "요청값 검증 실패 또는 Idempotency-Key 누락·빈 값",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "FORBIDDEN_NOT_OWNER: 본인 예약이 아님",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "예약 또는 관련 공간이 존재하지 않음",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = """
                        RESERVATION_STATE_CONFLICT: 예약 상태 또는 만료 조건으로 결제 불가.
                        SPACE_VERSION_MISMATCH: 공간 정보가 변경되어 재확인 필요.
                        """,
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "INSUFFICIENT_BALANCE: 크레딧 잔액 부족",
                    content = @Content
            )
    })
    @PostMapping("/{reservationId}/pay")
    public ResponseEntity<ApiResponse<ReservationResponse>> pay(
            @Parameter(hidden = true)
            @CurrentMember AuthPrincipal principal,
            @PathVariable Long reservationId,
            @Valid @RequestBody ReservationPayRequest request,
            @Parameter(
                    description = "결제 요청 식별 키. 동일 요청 재시도 시 같은 값을 사용합니다.",
                    example = "550e8400-e29b-41d4-a716-446655440000"
            )
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        ReservationResponse response = reservationPaymentConfirmService.confirm(
                principal.memberId(), reservationId, request.spaceVersion(), idempotencyKey
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /** 예약 취소 (예약자 본인만, CONFIRMED 상태에서 시작 전까지만 가능). */
    @Operation(
            summary = "예약 취소",
            description = """
                본인의 CONFIRMED 예약을 이용 시작 전에 취소합니다.
                취소 시 확보한 슬롯을 해제하고 출입 토큰을 폐기합니다.
                크레딧은 취소 시점에 따른 환불 정책을 적용해 처리합니다.
                """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "예약 취소 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "FORBIDDEN_NOT_OWNER: 본인 예약이 아님",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "RESERVATION_NOT_FOUND: 예약이 존재하지 않음",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "RESERVATION_STATE_CONFLICT: 예약 상태 또는 이용 시작 시각으로 인해 취소 불가",
                    content = @Content
            )
    })
    @PostMapping("/{reservationId}/cancel")
    public ResponseEntity<ApiResponse<ReservationResponse>> cancel(
            @Parameter(hidden = true)
            @CurrentMember AuthPrincipal principal,
            @PathVariable Long reservationId
    ) {
        ReservationResponse response = reservationCancelService.cancel(principal.memberId(), reservationId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /** 예약 연장. */
    @Operation(
            summary = "예약 이용 시간 연장",
            description = """
                본인 예약의 종료 시각을 연장하고 추가 요금을 크레딧으로 결제합니다.
                expectedEndTime에는 조회한 예약의 기존 종료 시각을 전달합니다.
                newEndTime에는 연장할 종료 시각을 전달합니다.
                추가 슬롯의 가용성과 운영시간, 예약 상태 및 잔액을 확인합니다.
                """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "예약 연장 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "입력값 검증 실패 또는 예약 시간 조건 오류",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "FORBIDDEN_NOT_OWNER: 본인 예약이 아님",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "예약 또는 관련 공간이 존재하지 않음",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "추가 슬롯 충돌 또는 기존 종료 시각·예약 상태 변경으로 인한 충돌",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "RESERVATION_EXTEND_NOT_ALLOWED / INSUFFICIENT_BALANCE: 연장 불가 또는 잔액 부족",
                    content = @Content
            )
    })
    @PostMapping("/{reservationId}/extend")
    public ResponseEntity<ApiResponse<ReservationResponse>> extend(
            @Parameter(hidden = true)
            @CurrentMember AuthPrincipal principal,
            @PathVariable Long reservationId,
            @Valid @RequestBody ReservationExtendRequest request
    ) {
        ReservationResponse response = reservationExtendService.extend(
                principal.memberId(), reservationId, request.expectedEndTime(), request.newEndTime()
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /** 체크아웃 (IN_USE -> COMPLETED, 되돌릴 수 없음). */
    @Operation(
            summary = "예약 체크아웃",
            description = """
                본인의 IN_USE 예약을 COMPLETED 상태로 변경하고 출입 토큰을 폐기합니다.
                체크아웃 후에는 이용 중 상태로 되돌릴 수 없습니다.
                일찍 체크아웃하더라도 남은 이용 시간에 대한 환불은 하지 않습니다.
                """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "체크아웃 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "FORBIDDEN_NOT_OWNER: 본인 예약이 아님",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "RESERVATION_NOT_FOUND: 예약이 존재하지 않음",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "RESERVATION_STATE_CONFLICT: 체크아웃할 수 없는 예약 상태",
                    content = @Content
            )
    })
    @PostMapping("/{reservationId}/check-out")
    public ResponseEntity<ApiResponse<ReservationResponse>> checkOut(
            @Parameter(hidden = true)
            @CurrentMember AuthPrincipal principal,
            @PathVariable Long reservationId
    ) {
        ReservationResponse response = reservationCheckOutService.checkOut(principal.memberId(), reservationId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /** 본인 예약 목록 (최신 시작 시각 순). */
    @Operation(
            summary = "내 예약 목록 조회",
            description = "로그인한 회원 본인의 예약 목록을 검색 조건(상태, 공간 이름 키워드)에 따라 최신 이용 시작 시각(startTime DESC, id DESC) 순으로 페이지 단위로 조회합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "본인 예약 목록 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "VALIDATION_FAILED: 잘못된 요청 파라미터(상태 enum 값 불일치 등)",
                    content = @Content
            )
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ReservationListResponse>>> getMyReservations(
            @Parameter(hidden = true)
            @CurrentMember AuthPrincipal principal,
            @ParameterObject
            @ModelAttribute ReservationSearchCondition condition,
            @ParameterObject Pageable pageable
    ) {
        Page<ReservationListResponse> response =
                reservationQueryService.getMyReservations(
                        principal.memberId(),
                        condition,
                        pageable
                );

        return ResponseEntity.ok(
                ApiResponse.success(PageResponse.from(response))
        );
    }

    /** 본인 예약 상세 + 상태 전이 이력. */
    @Operation(
            summary = "내 예약 상세 조회",
            description = "본인 예약의 이용 시간, 금액, 현재 상태 및 상태 변경 이력을 조회합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "예약 상세 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "FORBIDDEN_NOT_OWNER: 본인 예약이 아님",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "RESERVATION_NOT_FOUND: 예약이 존재하지 않음",
                    content = @Content
            )
    })
    @GetMapping("/{reservationId}")
    public ResponseEntity<ApiResponse<ReservationDetailResponse>> getMyReservation(
            @Parameter(hidden = true)
            @CurrentMember AuthPrincipal principal,
            @PathVariable Long reservationId
    ) {
        ReservationDetailResponse response =
                reservationQueryService.getMyReservation(principal.memberId(), reservationId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
