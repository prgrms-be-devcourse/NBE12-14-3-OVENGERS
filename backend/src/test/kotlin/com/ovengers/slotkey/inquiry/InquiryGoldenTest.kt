package com.ovengers.slotkey.inquiry

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.inquiry.dto.response.InquiryResponse
import com.ovengers.slotkey.inquiry.entity.Inquiry
import com.ovengers.slotkey.support.SurveyGoldenAssertions.assertGolden
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

/**
 * 문의 응답 DTO의 JSON 계약을 정적 골든으로 고정한다.
 * 기준 JSON은 DTO 전환 직전 Java 커밋 f2273a5에서 같은 입력으로 생성해 검토한 값이다.
 */
@JsonTest
class InquiryGoldenTest {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("답변 전 문의는 WAITING이며 답변 필드를 null로 유지한다")
    fun inquiryWaiting() {
        assertGolden(objectMapper, "inquiry/inquiry-waiting", InquiryResponse.from(inquiry()))
    }

    @Test
    @DisplayName("답변 후 문의는 ANSWERED이며 답변 내용·답변자·답변 시각을 담는다")
    fun inquiryAnswered() {
        val inquiry = inquiry().also { it.answer(1L, "확인 후 환불 처리했습니다.", BASE.plusHours(3)) }

        assertGolden(objectMapper, "inquiry/inquiry-answered", InquiryResponse.from(inquiry))
    }

    private fun inquiry(): Inquiry =
        Inquiry.create(7L, "환불 문의", "예약 취소 후 환불이 아직 안 됐어요.").also {
            ReflectionTestUtils.setField(it, "id", 21L)
            ReflectionTestUtils.setField(it, "createdAt", BASE)
        }

    private companion object {
        val BASE: LocalDateTime = LocalDateTime.of(2026, 10, 20, 9, 30, 15)
    }
}
