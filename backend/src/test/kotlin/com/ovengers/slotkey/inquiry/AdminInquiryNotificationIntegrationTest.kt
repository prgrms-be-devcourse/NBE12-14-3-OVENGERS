package com.ovengers.slotkey.inquiry

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.global.error.BusinessException
import com.ovengers.slotkey.inquiry.dto.request.InquiryAnswerRequest
import com.ovengers.slotkey.inquiry.dto.request.InquiryCreateRequest
import com.ovengers.slotkey.inquiry.dto.request.InquiryUpdateRequest
import com.ovengers.slotkey.inquiry.repository.InquiryRepository
import com.ovengers.slotkey.inquiry.service.AdminInquiryService
import com.ovengers.slotkey.inquiry.service.InquiryService
import com.ovengers.slotkey.support.ReservationIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class AdminInquiryNotificationIntegrationTest : ReservationIntegrationTestSupport() {
    @Autowired private lateinit var inquiryService: InquiryService

    @Autowired private lateinit var adminInquiryService: AdminInquiryService

    @Autowired private lateinit var inquiryRepository: InquiryRepository

    @Autowired private lateinit var objectMapper: ObjectMapper

    @AfterEach
    fun cleanInquiries() {
        jdbcTemplate.update("DELETE FROM outbox_event WHERE topic = 'notification-events'")
        inquiryRepository.deleteAllInBatch()
    }

    @Test
    fun answerRecordsNotificationEventForInquiryOwner() {
        val owner = createMember(0)
        val admin = createMember(0)
        val inquiry = inquiryService.create(owner, InquiryCreateRequest("환불 문의", "환불은 언제 되나요?"))

        adminInquiryService.answer(requireNotNull(inquiry.id), admin, InquiryAnswerRequest("내일 처리됩니다."))

        val row = jdbcTemplate.queryForMap("SELECT * FROM outbox_event WHERE event_type = 'INQUIRY_ANSWERED'")
        assertThat(row["event_type"]).isEqualTo("INQUIRY_ANSWERED")
        assertThat(row["partition_key"]).isEqualTo(owner.toString())
        assertThat(row["status"]).isEqualTo("PENDING")
        val payload = objectMapper.readTree(row["payload"] as String)
        assertThat(payload["recipientMemberId"].asLong()).isEqualTo(owner)
        assertThat(payload["referenceId"].asLong()).isEqualTo(inquiry.id)
        assertThat(payload["message"].asText()).contains("환불 문의")
    }

    @Test
    fun createRecordsAdminBroadcastEvent() {
        val owner = createMember(0)

        val inquiry = inquiryService.create(owner, InquiryCreateRequest("결제 오류", "결제가 두 번 됐어요."))

        val row = jdbcTemplate.queryForMap("SELECT * FROM outbox_event WHERE event_type = 'INQUIRY_CREATED'")
        assertThat(row["event_type"]).isEqualTo("INQUIRY_CREATED")
        assertThat(row["partition_key"]).isEqualTo("ADMIN")
        val payload = objectMapper.readTree(row["payload"] as String)
        assertThat(payload["recipientMemberId"].isNull).isTrue()
        assertThat(payload["referenceId"].asLong()).isEqualTo(inquiry.id)
        assertThat(payload["message"].asText()).contains("결제 오류")
    }

    @Test
    fun updatingInquiryDoesNotRecordNewEvent() {
        val owner = createMember(0)
        val inquiry = inquiryService.create(owner, InquiryCreateRequest("제목", "내용"))
        jdbcTemplate.update("DELETE FROM outbox_event WHERE topic = 'notification-events'")

        inquiryService.update(owner, requireNotNull(inquiry.id), InquiryUpdateRequest("수정", "수정 내용"))

        assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic = 'notification-events'", Int::class.javaObjectType),
        ).isZero()
    }

    @Test
    fun answerToUnknownInquiryRecordsNothing() {
        val admin = createMember(0)

        assertThatThrownBy { adminInquiryService.answer(Long.MAX_VALUE - 1, admin, InquiryAnswerRequest("답변")) }
            .isInstanceOf(BusinessException::class.java)

        assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE topic = 'notification-events'", Int::class.javaObjectType),
        ).isZero()
    }

    @Test
    fun longTitleIsTruncatedInMessage() {
        val owner = createMember(0)
        val admin = createMember(0)
        val inquiry = inquiryService.create(owner, InquiryCreateRequest("가".repeat(200), "내용"))

        adminInquiryService.answer(requireNotNull(inquiry.id), admin, InquiryAnswerRequest("답변"))

        val payload =
            objectMapper.readTree(
                jdbcTemplate.queryForObject("SELECT payload FROM outbox_event WHERE event_type = 'INQUIRY_ANSWERED'", String::class.java),
            )
        assertThat(payload["message"].asText().length).isLessThan(120)
    }
}
