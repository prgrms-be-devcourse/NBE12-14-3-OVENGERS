package com.ovengers.slotkey.member

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.audit.entity.AuditAction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.member.dto.response.AdminMemberCreditTransactionResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberDetailResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberReservationResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberResponse
import com.ovengers.slotkey.member.dto.response.AdminMemberStatusHistoryResponse
import com.ovengers.slotkey.member.dto.response.MemberResponse
import com.ovengers.slotkey.member.dto.response.MemberStatusChangeResponse
import com.ovengers.slotkey.member.entity.Member
import com.ovengers.slotkey.member.entity.MemberStatus
import com.ovengers.slotkey.reservation.entity.ReservationStatus
import com.ovengers.slotkey.support.SurveyGoldenAssertions.assertGolden
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

/**
 * 회원·관리자 회원 응답 DTO의 JSON 계약을 정적 골든으로 고정한다.
 * 기준 JSON은 DTO 전환 직전 Java 커밋 f2273a5에서 같은 입력으로 생성해 검토한 값이다.
 */
@JsonTest
class MemberGoldenTest {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("MemberResponse는 status 없이 역할·잔액·가입 시각을 담는다")
    fun memberResponse() {
        assertGolden(objectMapper, "member/member-response", MemberResponse(member()))
    }

    @Test
    @DisplayName("MemberStatusChangeResponse는 변경된 상태·사유·시각을 담는다")
    fun statusChangeSuspended() {
        assertGolden(
            objectMapper,
            "member/status-change-suspended",
            MemberStatusChangeResponse.of(suspendedMember(), "반복된 노쇼", BASE.plusDays(1)),
        )
    }

    @Test
    @DisplayName("상태 변경 이력이 없는 AdminMemberResponse는 마지막 변경 시각·사유를 null로 유지한다")
    fun adminMemberWithoutStatusChange() {
        assertGolden(
            objectMapper,
            "member/admin-member-without-status-change",
            AdminMemberResponse.of(member(), null, null),
        )
    }

    @Test
    @DisplayName("정지된 AdminMemberResponse는 마지막 변경 시각·사유를 담는다")
    fun adminMemberWithStatusChange() {
        assertGolden(
            objectMapper,
            "member/admin-member-with-status-change",
            AdminMemberResponse.of(suspendedMember(), BASE.plusDays(1), "반복된 노쇼"),
        )
    }

    @Test
    @DisplayName("AdminMemberDetailResponse는 회원 응답을 중첩하고 예약·정지 건수를 담는다")
    fun adminMemberDetail() {
        assertGolden(
            objectMapper,
            "member/admin-member-detail",
            AdminMemberDetailResponse(AdminMemberResponse.of(member(), null, null), 12L, 2L),
        )
    }

    @Test
    @DisplayName("AdminMemberReservationResponse는 공간 이름·예약 구간·상태·금액을 담는다")
    fun adminReservationConfirmed() {
        assertGolden(
            objectMapper,
            "member/admin-reservation-confirmed",
            AdminMemberReservationResponse(
                301L,
                11L,
                "판교 A룸",
                BASE
                    .plusDays(2)
                    .withHour(14)
                    .withMinute(0)
                    .withSecond(0),
                BASE
                    .plusDays(2)
                    .withHour(15)
                    .withMinute(30)
                    .withSecond(0),
                ReservationStatus.CONFIRMED,
                15000,
                BASE,
            ),
        )
    }

    @Test
    @DisplayName("삭제된 공간의 예약은 대체 이름을 쓰고 null 금액을 null로 유지한다")
    fun adminReservationDeletedSpaceNullAmount() {
        assertGolden(
            objectMapper,
            "member/admin-reservation-deleted-space-null-amount",
            AdminMemberReservationResponse(
                302L,
                99L,
                "삭제된 공간",
                BASE
                    .plusDays(3)
                    .withHour(10)
                    .withMinute(0)
                    .withSecond(0),
                BASE
                    .plusDays(3)
                    .withHour(10)
                    .withMinute(30)
                    .withSecond(0),
                ReservationStatus.CANCELLED,
                null,
                BASE.plusHours(1),
            ),
        )
    }

    @Test
    @DisplayName("AdminMemberCreditTransactionResponse는 거래 유형·금액·거래 후 잔액·사유를 담는다")
    fun adminCreditTransactionAdminGrant() {
        assertGolden(
            objectMapper,
            "member/admin-credit-transaction-admin-grant",
            AdminMemberCreditTransactionResponse(501L, CreditTransactionType.ADMIN_GRANT, 5000, 305000, "이벤트 보상", BASE),
        )
    }

    @Test
    @DisplayName("사유가 없는 차감 거래는 음수 금액과 null 사유를 유지한다")
    fun adminCreditTransactionNullReason() {
        assertGolden(
            objectMapper,
            "member/admin-credit-transaction-null-reason",
            AdminMemberCreditTransactionResponse(
                502L,
                CreditTransactionType.PENALTY,
                -7500,
                297500,
                null,
                BASE.plusHours(2),
            ),
        )
    }

    @Test
    @DisplayName("인증 잠금에 따른 시스템 정지 이력은 actorMemberId를 null로 유지한다")
    fun adminStatusHistorySystemLock() {
        assertGolden(
            objectMapper,
            "member/admin-status-history-system-lock",
            AdminMemberStatusHistoryResponse(
                701L,
                AuditAction.LOCK_MEMBER_BY_VERIFICATION,
                null,
                "이메일 인증 3차 실패",
                "ACTIVE",
                "SUSPENDED",
                BASE.plusMinutes(10),
            ),
        )
    }

    private fun member(): Member =
        Member("golden.user@example.com", "골든회원").also {
            ReflectionTestUtils.setField(it, "id", 7L)
            ReflectionTestUtils.setField(it, "balance", 300000)
            ReflectionTestUtils.setField(it, "createdAt", BASE)
        }

    private fun suspendedMember(): Member = member().also { it.updateStatus(MemberStatus.SUSPENDED) }

    private companion object {
        val BASE: LocalDateTime = LocalDateTime.of(2026, 10, 20, 9, 30, 15)
    }
}
