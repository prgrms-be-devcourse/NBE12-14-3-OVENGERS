package com.ovengers.slotkey.credit.pg

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties(prefix = "app.pg.toss")
@JvmRecord
data class TossPaymentsProperties(
    val baseUrl: URI,
    val secretKey: String,
    val connectTimeout: Duration,
    val readTimeout: Duration,
) {
    override fun toString(): String =
        "TossPaymentsProperties[baseUrl=$baseUrl, secretKey=***, connectTimeout=$connectTimeout, readTimeout=$readTimeout]"
}
