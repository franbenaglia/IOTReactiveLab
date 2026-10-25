package com.example.iotlab.reactive;

import static com.example.iotlab.support.Messages.telemetry;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.iotlab.config.MqttProperties;
import com.example.iotlab.mqtt.InboundMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class MqttReactorAdapterTest {

    static MqttReactorAdapter adapter(int receiveMaximum) {
        return new MqttReactorAdapter(new MqttProperties("tcp://localhost:1883", "test", "u", "",
                receiveMaximum, 3600, 30, 10, List.of("iot/devices/+/telemetry")));
    }

    @Test
    void buffersMessagesUntilThePipelineSubscribes() {
        MqttReactorAdapter a = adapter(10);
        InboundMessage m1 = telemetry("dev-001", 20, 1);
        InboundMessage m2 = telemetry("dev-001", 21, 2);
        assertThat(a.emit(m1)).isTrue();
        assertThat(a.emit(m2)).isTrue();
        assertThat(a.queued()).isEqualTo(2);

        StepVerifier.create(a.messages())
                .expectNext(m1, m2)
                .then(a::complete)
                .verifyComplete();
    }

    @Test
    void queueIsBoundedAndOverflowIsReportedNotThrown() {
        MqttReactorAdapter a = adapter(2); // 2*2 = 4, pero Queues.get impone mínimo 8
        assertThat(a.capacity()).isEqualTo(8);
        for (int i = 1; i <= 8; i++) {
            assertThat(a.emit(telemetry("dev-001", 20, i))).isTrue();
        }
        assertThat(a.emit(telemetry("dev-001", 20, 9))).isFalse(); // FAIL_OVERFLOW, sin excepción

        MqttReactorAdapter big = adapter(100);
        assertThat(big.capacity()).isEqualTo(256);
    }

    @Test
    void respectsDownstreamDemand() {
        MqttReactorAdapter a = adapter(10);
        for (int i = 1; i <= 5; i++) {
            a.emit(telemetry("dev-001", 20, i));
        }
        // Con demanda 2, el resto espera en la cola: eso es backpressure.
        StepVerifier.create(a.messages(), 2)
                .expectNextCount(2)
                .then(() -> assertThat(a.queued()).isEqualTo(3))
                .thenRequest(3)
                .expectNextCount(3)
                .then(a::complete)
                .verifyComplete();
    }

    @Test
    void unicastAcceptsASingleSubscriber() {
        MqttReactorAdapter a = adapter(10);
        a.messages().subscribe();
        StepVerifier.create(a.messages()).expectError(IllegalStateException.class).verify();
    }

    @Test
    void emitAfterCompleteIsRejected() {
        MqttReactorAdapter a = adapter(10);
        a.complete();
        assertThat(a.emit(telemetry("dev-001", 20, 1))).isFalse(); // FAIL_TERMINATED
    }
}
