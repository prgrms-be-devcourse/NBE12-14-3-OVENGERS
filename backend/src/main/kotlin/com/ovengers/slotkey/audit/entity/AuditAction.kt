package com.ovengers.slotkey.audit.entity

enum class AuditAction {
    REGISTER_SPACE, // 공간 등록
    MODIFY_SPACE, // 공간 수정
    SUSPEND_MEMBER, // 계정 정지
    REACTIVATE_MEMBER, // 계정 복구
    LOCK_MEMBER_BY_VERIFICATION, // 인증 실패로 인한 시스템 계정 잠금
    FORCE_CANCEL_RESERVATION, // 관리자 예약 강제 취소
    GRANT_CREDIT, // 관리자 크레딧 지급
    CHARGE_AMOUNT_MISMATCH, // PG 충전 금액 위변조 의심
    CHARGE_RECOVERY_MANUAL_REVIEW, // PG 충전 복구 수동 확인
}
