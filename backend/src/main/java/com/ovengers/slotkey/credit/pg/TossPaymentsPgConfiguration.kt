package com.ovengers.slotkey.credit.pg

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration(proxyBeanMethods = false)
@Profile("!test")
@EnableConfigurationProperties(TossPaymentsProperties::class)
class TossPaymentsPgConfiguration {
    @Bean
    fun tossPaymentsPgClient(
        restClientBuilder: RestClient.Builder,
        objectMapper: ObjectMapper,
        properties: TossPaymentsProperties,
    ): PgClient {
        val restClient =
            restClientBuilder
                .baseUrl(properties.baseUrl.toString())
                .requestFactory(requestFactory(properties))
                .build()
        return TossPaymentsPgClient(restClient, objectMapper, properties.secretKey)
    }

    companion object {
        @JvmStatic
        fun requestFactory(properties: TossPaymentsProperties): SimpleClientHttpRequestFactory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(properties.connectTimeout)
                setReadTimeout(properties.readTimeout)
            }
    }
}
