package com.ovengers.slotkey.credit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.credit.entity.CreditTransaction
import com.ovengers.slotkey.credit.entity.CreditTransactionType
import com.ovengers.slotkey.member.dto.response.AdminMemberCreditTransactionResponse
import com.ovengers.slotkey.member.entity.Member
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

/**
 * Kotlin 전환 전 Java CreditTransaction의 관리자 크레딧 응답 JSON 계약을 검증한다.
 * 골든 JSON은 Java 원본에서 검증한 뒤 고정하며 테스트 실행 시 재생성하지 않는다.
 */
@JsonTest
class CreditTransactionGoldenTest {
    private companion object {
        const val GOLDEN_DIRECTORY = "/golden/credit/"
    }

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val fixedAt = LocalDateTime.of(2026, 10, 25, 12, 0)

    @Test
    @DisplayName("관리자 크레딧 지급 응답은 Java 원본 JSON 계약을 유지한다")
    fun adminCreditWithReason() {
        // given
        val transaction =
            createTransaction(
                5000,
                CreditTransactionType.ADMIN_GRANT,
                5000,
                "관리자 지급",
            )

        // when
        val response = AdminMemberCreditTransactionResponse.from(transaction)
        val actual = serialize(response)

        // then
        assertThat(actual.path("transactionId").isIntegralNumber).isTrue()
        assertThat(actual.path("transactionId").asLong()).isEqualTo(10L)
        assertThat(actual.path("type").asText()).isEqualTo("ADMIN_GRANT")
        assertThat(actual.path("amount").asInt()).isEqualTo(5000)
        assertThat(actual.path("balanceAfter").asInt()).isEqualTo(5000)
        assertThat(actual.path("reason").asText()).isEqualTo("관리자 지급")

        assertMatchesGolden(actual, "admin-credit-with-reason.json")
    }

    @Test
    @DisplayName("지급 사유가 null인 응답은 Java 원본 JSON 계약을 유지한다")
    fun adminCreditWithNullReason() {
        // given
        val transaction =
            createTransaction(
                10000,
                CreditTransactionType.SIGNUP_GRANT,
                10000,
                null,
            )

        // when
        val response = AdminMemberCreditTransactionResponse.from(transaction)
        val actual = serialize(response)

        // then
        assertThat(actual.has("reason")).isTrue()
        assertThat(actual.path("reason").isNull).isTrue()
        assertThat(actual.path("amount").asInt()).isEqualTo(10000)

        assertMatchesGolden(actual, "admin-credit-null-reason.json")
    }

    private fun createTransaction(
        amount: Int,
        type: CreditTransactionType,
        balanceAfter: Int,
        reason: String?,
    ): CreditTransaction {
        val member = Member("credit-golden@test.com", "크레딧테스트")

        val transaction =
            CreditTransaction(
                member,
                amount,
                type,
                null,
                balanceAfter,
                reason,
                fixedAt,
            )

        // DB 생성 ID 대신 Java/Kotlin에서 동일한 고정 ID를 사용한다.
        ReflectionTestUtils.setField(transaction, "id", 10L)

        return transaction
    }

    private fun serialize(value: Any): JsonNode {
        val json = objectMapper.writeValueAsString(value)
        return objectMapper.readTree(json)
    }

    private fun assertMatchesGolden(
        actual: JsonNode,
        goldenFile: String,
    ) {
        val expected =
            requireNotNull(
                javaClass.getResourceAsStream(GOLDEN_DIRECTORY + goldenFile),
            ) {
                "골든 파일이 존재해야 한다: $goldenFile"
            }.use { source ->
                objectMapper.readTree(source)
            }

        assertThat(actual)
            .`as`("Java 원본 응답과 골든 JSON이 같아야 한다: %s", goldenFile)
            .isEqualTo(expected)
    }
}
