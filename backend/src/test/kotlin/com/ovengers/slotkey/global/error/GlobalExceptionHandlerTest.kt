package com.ovengers.slotkey.global.error

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MaxUploadSizeExceededException

class GlobalExceptionHandlerTest {
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(TestController())
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없으면 전용 오류 코드를 반환한다")
    fun shouldReturnIdempotencyKeyRequiredWhenHeaderIsMissing() {
        mockMvc
            .perform(get("/test/idempotency"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
            .andExpect(jsonPath("$.message").value("Idempotency-Key 헤더가 필요합니다."))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    @DisplayName("처리되지 않은 예외는 공통 서버 오류 형식으로 반환한다")
    fun shouldReturnInternalServerErrorWhenUnexpectedExceptionOccurs() {
        mockMvc
            .perform(get("/test/exception"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    @DisplayName("BusinessException은 ErrorCode의 HTTP 상태·코드·기본 메시지로 매핑한다")
    fun shouldReturnBusinessErrorContract() {
        mockMvc
            .perform(get("/test/business"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("존재하지 않는 오피스입니다."))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    @DisplayName("BusinessException의 상세 메시지는 오류 코드 기본 메시지를 대체한다")
    fun shouldReturnBusinessErrorWithCustomMessage() {
        mockMvc
            .perform(get("/test/business-detail"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("상세 메시지"))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    @DisplayName("@Valid 실패는 필드 메시지를 합쳐 VALIDATION_FAILED로 반환한다")
    fun shouldReturnValidationFailedWithFieldMessage() {
        mockMvc
            .perform(
                post("/test/validation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"\"}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("이름은 필수입니다."))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    @DisplayName("읽을 수 없는 요청 본문은 VALIDATION_FAILED로 반환한다")
    fun shouldReturnValidationFailedWhenBodyIsUnreadable() {
        mockMvc
            .perform(
                post("/test/validation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("요청 데이터가 올바르지 않습니다."))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    @DisplayName("파라미터 타입 불일치는 VALIDATION_FAILED와 안내 메시지로 반환한다")
    fun shouldReturnValidationFailedWhenParameterTypeIsInvalid() {
        mockMvc
            .perform(
                get("/test/type-mismatch")
                    .param("count", "not-a-number"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("요청 파라미터의 형식이 올바르지 않습니다."))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    @DisplayName("Idempotency-Key 외 필수 헤더 누락은 VALIDATION_FAILED로 반환한다")
    fun shouldReturnValidationFailedWhenOtherHeaderIsMissing() {
        mockMvc
            .perform(get("/test/required-header"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("요청 데이터가 올바르지 않습니다."))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    @DisplayName("업로드 크기 초과는 413 IMAGE_SIZE_EXCEEDED로 반환한다")
    fun shouldReturnPayloadTooLargeForMaxUploadSize() {
        mockMvc
            .perform(get("/test/max-upload"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.status").value("FAIL"))
            .andExpect(jsonPath("$.code").value("IMAGE_SIZE_EXCEEDED"))
            .andExpect(jsonPath("$.message").value("이미지 파일 크기는 최대 5MB까지 가능합니다."))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @RestController
    class TestController {
        @GetMapping("/test/idempotency")
        fun requireIdempotencyKey(
            @RequestHeader("Idempotency-Key") idempotencyKey: String,
        ): String = idempotencyKey

        @GetMapping("/test/required-header")
        fun requireHeader(
            @RequestHeader("X-Trace-Id") traceId: String,
        ): String = traceId

        @GetMapping("/test/exception")
        fun throwUnexpectedException(): String = throw IllegalStateException("외부에 노출하면 안 되는 내부 오류")

        @GetMapping("/test/business")
        fun throwBusinessException(): String = throw BusinessException(ErrorCode.SPACE_NOT_FOUND)

        @GetMapping("/test/business-detail")
        fun throwBusinessExceptionWithDetail(): String = throw BusinessException(ErrorCode.SPACE_NOT_FOUND, "상세 메시지")

        @GetMapping("/test/type-mismatch")
        fun typeMismatch(
            @RequestParam("count") count: Int,
        ): String = count.toString()

        @GetMapping("/test/max-upload")
        fun throwMaxUploadSizeExceeded(): String = throw MaxUploadSizeExceededException(1024L)

        @PostMapping("/test/validation")
        fun validate(
            @Valid @RequestBody request: ValidationRequest,
        ): String? = request.name
    }

    @JvmRecord
    data class ValidationRequest(
        @field:NotBlank(message = "이름은 필수입니다.")
        val name: String?,
    )
}
