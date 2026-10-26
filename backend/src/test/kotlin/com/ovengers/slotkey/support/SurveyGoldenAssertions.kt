package com.ovengers.slotkey.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat

/** Static output captured from Java a380133. Never writes or normalizes fixtures. */
object SurveyGoldenAssertions {
    fun readGolden(
        mapper: ObjectMapper,
        path: String,
    ): JsonNode {
        val input = SurveyGoldenAssertions::class.java.getResourceAsStream("/golden/$path.json")
        assertThat(input).describedAs("Java a380133 golden: %s", path).isNotNull()
        return requireNotNull(input).use { mapper.readTree(it) }
    }

    fun assertGolden(
        mapper: ObjectMapper,
        path: String,
        value: Any,
    ) {
        val actual = mapper.readTree(mapper.writeValueAsString(value))
        assertThat(actual).isEqualTo(readGolden(mapper, path))
    }
}
