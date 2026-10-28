package com.ovengers.slotkey.notification

import com.ovengers.slotkey.notification.consumer.NotificationEventListener
import com.ovengers.slotkey.notification.consumer.SlotReleasedListener
import com.ovengers.slotkey.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.kafka.listener.CommonErrorHandler

/** 기본 설정(slotkey.kafka.enabled=false, CI)에서는 브로커 없이 기동하고 리스너·에러 핸들러 빈이 없어야 한다. */
class KafkaConsumerDisabledTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun listenerAndErrorHandlerBeansAreAbsentByDefault() {
        assertThat(context.getBeanNamesForType(SlotReleasedListener::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(NotificationEventListener::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(CommonErrorHandler::class.java)).isEmpty()
    }
}
