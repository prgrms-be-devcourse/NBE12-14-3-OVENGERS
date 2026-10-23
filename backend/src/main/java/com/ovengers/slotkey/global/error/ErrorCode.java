package com.ovengers.slotkey.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    // ── 공통 ──────────────────────────────────────────
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "요청 데이터가 올바르지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."),
    // ── Member ─────────────────────────────────────────
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "존재하지 않는 회원입니다."),
    PASSWORD_CONFIRM_MISMATCH(HttpStatus.BAD_REQUEST, "PASSWORD_CONFIRM_MISMATCH", "비밀번호가 일치하지 않습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "로그인 후 이용해주세요."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다."),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "접근 권한이 없습니다."),
    TARGET_IS_ADMIN(HttpStatus.UNPROCESSABLE_ENTITY, "TARGET_IS_ADMIN", "관리자 계정은 정지하거나 복구할 수 없습니다."),
    SELF_GRANT_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "SELF_GRANT_NOT_ALLOWED", "자기 자신에게는 크레딧을 지급할 수 없습니다."),
    ACCOUNT_WITHDRAWN(HttpStatus.UNAUTHORIZED, "ACCOUNT_WITHDRAWN", "탈퇴한 계정입니다."),
    MEMBER_WITHDRAWN(HttpStatus.CONFLICT, "MEMBER_WITHDRAWN", "탈퇴한 회원의 상태를 변경하거나 크레딧을 지급할 수 없습니다."),
    CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_MISMATCH", "현재 비밀번호가 일치하지 않습니다."),
    // ── 이메일 인증 ────────────────────────────────────
    VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_MISMATCH", "인증번호가 일치하지 않습니다."),
    VERIFICATION_CHALLENGE_INVALID(HttpStatus.BAD_REQUEST, "VERIFICATION_CHALLENGE_INVALID", "인증번호가 만료되었거나 사용할 수 없습니다. 다시 요청해주세요."),
    VERIFICATION_TEMPORARILY_BLOCKED(HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_TEMPORARILY_BLOCKED", "인증 시도가 일시적으로 제한되었습니다."),
    VERIFICATION_ADMIN_LOCKED(HttpStatus.FORBIDDEN, "VERIFICATION_ADMIN_LOCKED", "이메일 인증이 정지되었습니다. 관리자에게 문의해주세요."),
    VERIFICATION_RESEND_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_RESEND_TOO_SOON", "인증번호는 마지막 발송 후 30초가 지나야 다시 요청할 수 있습니다."),
    VERIFICATION_MAIL_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "VERIFICATION_MAIL_FAILED", "인증 메일을 발송하지 못했습니다. 잠시 후 다시 시도해주세요."),
    VERIFICATION_GRANT_INVALID(HttpStatus.BAD_REQUEST, "VERIFICATION_GRANT_INVALID", "인증 증표가 만료되었거나 사용할 수 없습니다. 이메일 인증을 다시 진행해주세요."),
    //── Refresh_token ─────────────────────────────────────────
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
    LOGIN_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "로그인 요청이 일시적으로 제한되었습니다."),
    ACCOUNT_INACTIVE(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "이용이 제한된 계정입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "리프레시 토큰이 유효하지 않습니다. 다시 로그인해주세요."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "액세스 토큰이 유효하지 않습니다."),
    AUTHENTICATION_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTHENTICATION_SERVICE_UNAVAILABLE", "인증 상태를 확인할 수 없습니다. 잠시 후 다시 시도해주세요."),
    // ── Space ─────────────────────────────────────────
    SPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "SPACE_NOT_FOUND", "존재하지 않는 오피스입니다."),
    SPACE_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "SPACE_INACTIVE", "예약이 불가능한 오피스입니다."),
    INVALID_PRICE_UNIT(HttpStatus.BAD_REQUEST, "INVALID_PRICE_UNIT", "요금은 100원 단위여야 합니다."),
    INVALID_OPERATING_HOURS(HttpStatus.BAD_REQUEST, "INVALID_OPERATING_HOURS", "운영 종료 시각은 시작 시각보다 늦어야 합니다."),
    SPACE_OPERATING_HOURS_CONFLICT(HttpStatus.CONFLICT, "SPACE_OPERATING_HOURS_CONFLICT",
                    "기존 예약과 충돌하여 운영시간을 수정할 수 없습니다."),
    IMAGE_FILE_EMPTY(HttpStatus.BAD_REQUEST, "IMAGE_FILE_EMPTY", "업로드할 이미지 파일이 비어 있습니다."),
    INVALID_IMAGE_FORMAT(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_FORMAT", "지원하지 않거나 올바르지 않은 이미지 형식입니다. (JPEG, PNG만 지원)"),
    IMAGE_SIZE_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_SIZE_EXCEEDED", "이미지 파일 크기는 최대 5MB까지 가능합니다."),
    IMAGE_DIMENSIONS_EXCEEDED(HttpStatus.BAD_REQUEST, "IMAGE_DIMENSIONS_EXCEEDED", "이미지 해상도는 최대 4096x4096까지 가능합니다."),
    IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "요청한 이미지를 찾을 수 없습니다."),
    IMAGE_STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_STORAGE_ERROR", "이미지 저장 처리에 실패했습니다."),

    // ── Reservation ─────────────────────────────────────
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "존재하지 않는 예약입니다."),
    RESERVATION_SLOT_CONFLICT(HttpStatus.CONFLICT, "RESERVATION_SLOT_CONFLICT", "이미 예약된 시간대입니다."),
    RESERVATION_STATE_CONFLICT(HttpStatus.CONFLICT, "RESERVATION_STATE_CONFLICT", "현재 예약 상태에서는 처리할 수 없는 요청입니다."),
    RESERVATION_EXTEND_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "RESERVATION_EXTEND_NOT_ALLOWED", "연장할 수 없는 예약입니다."),
    FORBIDDEN_NOT_OWNER(HttpStatus.FORBIDDEN, "FORBIDDEN_NOT_OWNER", "본인 예약이 아닙니다."),
    SPACE_VERSION_MISMATCH(HttpStatus.CONFLICT, "SPACE_VERSION_MISMATCH", "오피스 정보가 변경되었습니다. 다시 확인해주세요."),
    INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_BALANCE", "크레딧 잔액이 부족합니다."),
    INVALID_RESERVATION_TIME(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION_TIME", "예약 가능한 시간이 아닙니다."),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 헤더가 필요합니다."),
    WITHDRAWAL_ACTIVE_RESERVATION(HttpStatus.CONFLICT, "WITHDRAWAL_ACTIVE_RESERVATION", "진행 중이거나 예정된 예약이 있어 탈퇴할 수 없습니다."),

    // ── PG Credit Charge ───────────────────────────────
    CHARGE_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "CHARGE_ORDER_NOT_FOUND", "존재하지 않는 충전 주문입니다."),
    CHARGE_ORDER_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "CHARGE_ORDER_AMOUNT_MISMATCH", "충전 주문 금액이 일치하지 않습니다."),
    CHARGE_ORDER_STATE_CONFLICT(HttpStatus.CONFLICT, "CHARGE_ORDER_STATE_CONFLICT", "현재 충전 주문 상태에서는 승인할 수 없습니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "다른 충전 주문에 사용한 Idempotency-Key입니다."),
    PAYMENT_DECLINED(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_DECLINED", "결제가 거절되었습니다."),
    PG_CONFIRMATION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "PG_CONFIRMATION_ERROR", "PG 승인 요청 설정을 확인해주세요."),

    // ── Door Access ──────────────────────────────────────
    ACCESS_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "ACCESS_TOKEN_NOT_FOUND", "출입 토큰을 찾을 수 없습니다."),
    ACTIVE_ACCESS_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "ACTIVE_ACCESS_TOKEN_NOT_FOUND", "활성 출입 토큰을 찾을 수 없습니다."),
    ACCESS_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "ACCESS_LOG_NOT_FOUND", "출입 기록을 찾을 수 없습니다."),
    ACCESS_DENY_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "ACCESS_DENY_REASON_REQUIRED", "출입 거절 사유는 필수입니다."),

    // ── Inquiry (Q&A) ──────────────────────────────────
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "존재하지 않는 문의입니다."),
    INQUIRY_ALREADY_ANSWERED(HttpStatus.UNPROCESSABLE_ENTITY, "INQUIRY_ALREADY_ANSWERED", "이미 답변이 등록된 문의는 수정할 수 없습니다."),

    // ── Waitlist (예약 대기) ────────────────────────────
    WAITLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "WAITLIST_NOT_FOUND", "존재하지 않는 대기 신청입니다."),
    WAITLIST_NOT_NEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "WAITLIST_NOT_NEEDED",
                    "해당 시간대는 비어 있어 바로 예약할 수 있습니다."),
    WAITLIST_ALREADY_HOLDING(HttpStatus.UNPROCESSABLE_ENTITY, "WAITLIST_ALREADY_HOLDING",
                    "같은 시간대에 본인 예약이 있어 대기를 신청할 수 없습니다."),
    WAITLIST_DUPLICATE(HttpStatus.CONFLICT, "WAITLIST_DUPLICATE", "이미 신청한 대기입니다."),
    WAITLIST_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "WAITLIST_LIMIT_EXCEEDED",
                    "대기 신청은 최대 3건까지 가능합니다."),

    // ── Space 검색 ─────────────────────────────────────
    INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "시작 시각은 종료 시각보다 빨라야 합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
