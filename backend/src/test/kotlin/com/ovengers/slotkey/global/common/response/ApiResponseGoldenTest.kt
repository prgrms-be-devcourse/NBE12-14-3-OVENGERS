package com.ovengers.slotkey.global.common.response

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.global.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

/**
 * Kotlin 전환 전 Java 공통 응답의 JSON 계약을 고정한다.
 * 기대값은 `src/test/resources/golden/common-response/`의 정적 파일이며, 구현 출력으로 매번 재생성하지 않는다.
 */
@JsonTest
class ApiResponseGoldenTest {
    private companion object {
        const val GOLDEN_DIRECTORY = "/golden/common-response/"
    }

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("성공 응답은 status/code/message/data 필드와 값을 유지한다")
    fun successWithData() {
        assertMatchesGolden(
            serialize(ApiResponse.success(SampleItem(1L, "테스트 공간", true))),
            "success-with-data.json",
        )
    }

    @Test
    @DisplayName("데이터 없는 성공 응답은 NON_NULL 규칙으로 data 필드를 생략한다")
    fun successWithoutDataOmitsDataField() {
        val actual = serialize(ApiResponse.success<Any>())

        assertThat(actual.has("data")).isFalse
        assertMatchesGolden(actual, "success-without-data.json")
    }

    @Test
    @DisplayName("NON_NULL은 최상위 ApiResponse에만 적용되고 data 내부 null 필드는 유지한다")
    fun successWithNestedNullFieldsKeepsNulls() {
        assertMatchesGolden(
            serialize(ApiResponse.success(SampleItem(2L, null, null))),
            "success-with-null-fields.json",
        )
    }

    @Test
    @DisplayName("실패 응답은 ErrorCode의 기본 코드·메시지를 사용하고 data를 생략한다")
    fun failWithDefaultMessage() {
        val actual = serialize(ApiResponse.fail<Any>(ErrorCode.SPACE_NOT_FOUND))

        assertThat(actual.has("data")).isFalse
        assertMatchesGolden(actual, "fail-with-default-message.json")
    }

    @Test
    @DisplayName("실패 응답의 사용자 지정 메시지는 ErrorCode 기본 메시지를 대체한다")
    fun failWithCustomMessage() {
        assertMatchesGolden(
            serialize(ApiResponse.fail<Any>(ErrorCode.SPACE_NOT_FOUND, "상세 메시지")),
            "fail-with-custom-message.json",
        )
    }

    @Test
    @DisplayName("PageResponse는 content/page/size/totalElements/totalPages 숫자 계약을 유지한다")
    fun pageResponse() {
        val page =
            PageImpl(
                listOf(PageItem(11L, "공간 A"), PageItem(12L, "공간 B")),
                PageRequest.of(1, 20),
                45,
            )

        val response = PageResponse.from(page)

        assertThat(response.page).isEqualTo(1)
        assertThat(response.size).isEqualTo(20)
        assertThat(response.totalElements).isEqualTo(45)
        assertThat(response.totalPages).isEqualTo(3)
        assertMatchesGolden(serialize(response), "page-response.json")
        assertMatchesGolden(serialize(ApiResponse.success(response)), "success-with-page.json")
    }

    @Test
    @DisplayName("빈 페이지는 totalElements=0, totalPages=0을 유지한다")
    fun emptyPageResponse() {
        val response =
            PageResponse.from(
                PageImpl<PageItem>(emptyList(), PageRequest.of(0, 20), 0),
            )

        assertThat(response.totalElements).isZero
        assertThat(response.totalPages).isZero
        assertMatchesGolden(serialize(response), "empty-page-response.json")
    }

    @Test
    @DisplayName("totalElements가 int 범위를 넘어도 long 값과 정확한 JSON 숫자를 유지한다")
    fun pageResponseKeepsLongTotalElements() {
        val totalElements = 2_147_483_648L
        val response =
            PageResponse.from(
                PageImpl(
                    listOf(PageItem(21L, "공간 C")),
                    PageRequest.of(0, 20),
                    totalElements,
                ),
            )

        assertThat(response.totalElements).isEqualTo(totalElements)
        assertThat(response.totalPages).isEqualTo(107_374_183)

        val actual = serialize(response)
        assertThat(actual.get("totalElements").isIntegralNumber).isTrue
        assertThat(actual.get("totalElements").longValue()).isEqualTo(totalElements)
        assertMatchesGolden(actual, "large-total-elements-page-response.json")
    }

    private fun serialize(value: Any?): JsonNode = objectMapper.readTree(objectMapper.writeValueAsString(value))

    private fun assertMatchesGolden(
        actual: JsonNode,
        goldenFile: String,
    ) {
        val golden =
            requireNotNull(javaClass.getResourceAsStream(GOLDEN_DIRECTORY + goldenFile)) {
                "골든 파일이 존재해야 한다: $goldenFile"
            }
        assertThat(actual)
            .`as`("전환 전 Java 응답과 골든 JSON이 같아야 한다: %s", goldenFile)
            .isEqualTo(objectMapper.readTree(golden))
    }

    private data class SampleItem(
        val id: Long?,
        val name: String?,
        val active: Boolean?,
    )

    private data class PageItem(
        val id: Long?,
        val name: String?,
    )
}
