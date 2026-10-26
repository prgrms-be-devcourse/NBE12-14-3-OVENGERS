package com.ovengers.slotkey.credit.webhook

import com.fasterxml.jackson.databind.ObjectMapper
import com.ovengers.slotkey.support.SurveyGoldenAssertions.assertGolden
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import java.util.Locale

/** CONFIRMED is already covered by FridayRemainingDtoGoldenTest; add only missing values. */
@JsonTest
class PgWebhookResultGoldenTest {
    @Autowired
    private lateinit var mapper: ObjectMapper

    @ParameterizedTest
    @EnumSource(value = PgWebhookEventResult::class, names = ["CONFIRMED"], mode = EnumSource.Mode.EXCLUDE)
    fun missingResultKeepsJavaEnumString(result: PgWebhookEventResult) {
        val situation = result.name.lowercase(Locale.ROOT).replace('_', '-')
        assertGolden(mapper, "credit/webhook-$situation-result", result)
    }
}
