package com.ovengers.slotkey.outbox;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** CI용 발행 포트. 실제 Kafka 없이 relay의 성공·실패 경로를 만든다. */
public class FakeOutboxPublisher implements OutboxPublisher {

    public record Sent(String topic, String key, String payload) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private volatile Predicate<String> failWhen = payload -> false;

    @Override
    public void publish(String topic, String key, String payload) {
        if (failWhen.test(payload)) {
            throw new OutboxPublishException("fake publish failure");
        }
        sent.add(new Sent(topic, key, payload));
    }

    public void failWhen(Predicate<String> condition) {
        this.failWhen = condition;
    }

    public void reset() {
        sent.clear();
        failWhen = payload -> false;
    }

    public List<Sent> sent() {
        return sent;
    }
}
