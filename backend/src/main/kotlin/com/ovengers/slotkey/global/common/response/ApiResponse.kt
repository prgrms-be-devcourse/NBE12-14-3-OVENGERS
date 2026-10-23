package com.ovengers.slotkey.global.common.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.ovengers.slotkey.global.error.ErrorCode

/**
 * 공통 API 응답 래퍼.
 *
 * <pre>
 * 성공: { "status": "SUCCESS", "code": "OK", "message": "...", "data": {...} }
 * 실패: { "status": "FAIL",    "code": "SPACE_NOT_FOUND", "message": "...", "data": null }
 * </pre>
 */
@JvmRecord
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val status: ResponseStatus,
    val code: String,
    val message: String?,
    val data: T?,
) {
    companion object {
        private const val SUCCESS_CODE = "OK"
        private const val SUCCESS_MESSAGE = "요청이 성공적으로 처리되었습니다."

        /** 성공 응답 (200 OK) */
        @JvmStatic
        fun <T> success(data: T?): ApiResponse<T> = ApiResponse(ResponseStatus.SUCCESS, SUCCESS_CODE, SUCCESS_MESSAGE, data)

        /** 성공 응답 (data 없음) */
        @JvmStatic
        fun <T> success(): ApiResponse<T> = ApiResponse(ResponseStatus.SUCCESS, SUCCESS_CODE, SUCCESS_MESSAGE, null)

        /** 실패 응답 */
        @JvmStatic
        fun <T> fail(errorCode: ErrorCode): ApiResponse<T> =
            errorCode.let {
                ApiResponse(ResponseStatus.FAIL, it.code, it.message, null)
            }

        /** 실패 응답 (커스텀 메시지) */
        @JvmStatic
        fun <T> fail(
            errorCode: ErrorCode,
            message: String?,
        ): ApiResponse<T> = ApiResponse(ResponseStatus.FAIL, errorCode.code, message, null)
    }
}
