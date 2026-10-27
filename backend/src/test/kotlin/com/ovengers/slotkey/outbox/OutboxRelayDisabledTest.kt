package com.ovengers.slotkey.outbox

import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

/** 기본 설정(slotkey.kafka.enabled=false)에서는 브로커 없이 기동하고 relay·Kafka 빈이 없어야 한다. */
class OutboxRelayDisabledTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun relayAndKafkaBeansAreAbsentByDefault() {
        assertThat(context.getBeanNamesForType(OutboxRelay::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(OutboxPublisher::class.java)).isEmpty()
    }
}
