package com.ovengers.slotkey.notification

import com.ovengers.slotkey.notification.consumer.SlotReleasedListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SlotReleasedListenerContractTest {
    @Test
    fun listenerKeepsTopicAndConsumerGroup() {
        assertThat(SlotReleasedListener.TOPIC).isEqualTo("slot-released")
        assertThat(SlotReleasedListener.GROUP_ID).isEqualTo("slotkey-waitlist-notifier")
    }
}
