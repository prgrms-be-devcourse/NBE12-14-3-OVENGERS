package com.ovengers.slotkey.access.entity

enum class AccessDenyReason {
    TOKEN_NOT_FOUND,
    TOKEN_REVOKED,
    RESERVATION_NOT_ACTIVE,
    OUTSIDE_ALLOWED_TIME,
    SPACE_MISMATCH,
    MEMBER_MISMATCH,
    MEMBER_INACTIVE,
}
