package com.ovengers.slotkey.global.error

open class BusinessException(
    open val errorCode: ErrorCode,
    detail: String?,
) : RuntimeException(detail) {
    constructor(errorCode: ErrorCode) : this(errorCode, errorCode.message)
}
