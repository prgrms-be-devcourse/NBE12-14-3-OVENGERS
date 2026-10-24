package com.ovengers.slotkey.notification.consumer

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * 컨슈머 실패 처리. 1초 간격 2회 재시도(총 3회)로 제한하고, 소진되면 건너뛴다.
 * DLT(dead-letter topic)는 3차 범위다 — 건너뛴 이벤트는 로그로만 남는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["slotkey.kafka.enabled"], havingValue = "true")
class KafkaConsumerConfig {
    @Bean
    fun kafkaConsumerErrorHandler(): DefaultErrorHandler =
        DefaultErrorHandler(FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES)).apply {
            addNotRetryableExceptions(IllegalArgumentException::class.java)
        }

    companion object {
        const val RETRY_INTERVAL_MS = 1_000L
        const val MAX_RETRIES = 2L
    }
}
