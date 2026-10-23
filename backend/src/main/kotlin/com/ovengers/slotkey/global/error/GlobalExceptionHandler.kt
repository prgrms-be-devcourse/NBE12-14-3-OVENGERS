package com.ovengers.slotkey.global.error

import com.ovengers.slotkey.global.common.response.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException

@RestControllerAdvice
open class GlobalExceptionHandler {
    /** 비즈니스 예외 처리 */
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(e: BusinessException): ResponseEntity<ApiResponse<Void>> = fail(e.errorCode, e.message)

    /** Bean Validation 실패 (@Valid) 처리 */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(e: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Void>> {
        val message =
            e.bindingResult.fieldErrors
                .map { it.defaultMessage }
                .joinToString(", ")
        return fail(ErrorCode.VALIDATION_FAILED, message)
    }

    /** 파라미터 타입 불일치 (예: 날짜/시간 파싱 오류 등) 처리 */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatchException(e: MethodArgumentTypeMismatchException): ResponseEntity<ApiResponse<Void>> =
        fail(ErrorCode.VALIDATION_FAILED, "요청 파라미터의 형식이 올바르지 않습니다.")

    /** 파일/요청 크기 초과 처리 (413) */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSizeExceededException(e: MaxUploadSizeExceededException): ResponseEntity<ApiResponse<Void>> =
        fail(ErrorCode.IMAGE_SIZE_EXCEEDED)

    /** 필수 요청 헤더 누락 처리 */
    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingRequestHeaderException(e: MissingRequestHeaderException): ResponseEntity<ApiResponse<Void>> {
        val errorCode =
            if (e.headerName.equals("Idempotency-Key", ignoreCase = true)) {
                ErrorCode.IDEMPOTENCY_KEY_REQUIRED
            } else {
                ErrorCode.VALIDATION_FAILED
            }
        return fail(errorCode)
    }

    /** 처리되지 않은 서버 예외를 공통 응답 형식으로 반환 */
    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ApiResponse<Void>> {
        log.error("처리되지 않은 서버 예외가 발생했습니다.", e)
        return fail(ErrorCode.INTERNAL_SERVER_ERROR)
    }

    /** 요청 본문을 읽거나 변환할 수 없는 경우 처리 */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(e: HttpMessageNotReadableException): ResponseEntity<ApiResponse<Void>> =
        fail(ErrorCode.VALIDATION_FAILED)

    private fun fail(errorCode: ErrorCode): ResponseEntity<ApiResponse<Void>> =
        errorCode.let {
            ResponseEntity.status(it.httpStatus).body(ApiResponse.fail<Void>(it))
        }

    private fun fail(
        errorCode: ErrorCode,
        message: String?,
    ): ResponseEntity<ApiResponse<Void>> =
        errorCode.let {
            ResponseEntity.status(it.httpStatus).body(ApiResponse.fail<Void>(it, message))
        }

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
