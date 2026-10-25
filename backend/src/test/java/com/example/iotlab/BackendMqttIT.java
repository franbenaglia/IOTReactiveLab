package com.example.iotlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.iotlab.metrics.PipelineMetrics;
import com.example.iotlab.mqtt.MqttHealthIndicator;
import com.example.iotlab.support.Messages;
import com.example.iotlab.support.MosquittoContainer;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** La aplicación completa contra un Mosquitto real: MQTT → Sink → Pipeline A → PUBACK. */
@SpringBootTest(properties = "spring.config.import=")
@Testcontainers
class BackendMqttIT {

    @Container
    static final MosquittoContainer broker = new MosquittoContainer();

    @DynamicPropertySource
    static void mqtt(DynamicPropertyRegistry r) {
        r.add("iotlab.mqtt.server-uri", broker::serverUri);
        r.add("iotlab.mqtt.password", () -> "");
        r.add("iotlab.mqtt.client-id", () -> "it-backend-app");
    }

    @Autowired MeterRegistry registry;
    @Autowired MqttHealthIndicator health;

    double counter(String name, String... tags) {
        var c = registry.find(PipelineMetrics.PREFIX + name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    double gauge(String name) {
        return registry.get(PipelineMetrics.PREFIX + name).gauge().value();
    }

    static String now() {
        return java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
    }

    @Test
    void processesValidInvalidAndStatusMessagesAndAcksEverything() throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(() -> health.health().getStatus().equals(Status.UP));

        MqttAsyncClient pub = new MqttAsyncClient(broker.serverUri(), "it-device-sim", new MemoryPersistence());
        pub.connect().waitForCompletion(5000);
        for (int i = 1; i <= 300; i++) {
            String id = "esp32-sim-" + String.format("%05d", i % 10 + 1);
            pub.publish("iot/devices/" + id + "/telemetry",
                    Messages.telemetryJson(id, 20 + i % 5, i, now()).getBytes(StandardCharsets.UTF_8), 1, false);
        }
        pub.publish("iot/devices/esp32-sim-00001/telemetry", "{roto".getBytes(StandardCharsets.UTF_8), 1, false);
        pub.publish("iot/devices/esp32-sim-00001/telemetry",
                Messages.telemetryJson("esp32-sim-00001", 999, 1, now()).getBytes(StandardCharsets.UTF_8), 1, false);
        pub.publish("iot/devices/esp32-sim-00001/status",
                "{\"deviceId\":\"esp32-sim-00001\",\"status\":\"online\",\"timestamp\":\"2026-10-25T12:30:00Z\"}"
                        .getBytes(StandardCharsets.UTF_8), 1, false).waitForCompletion(5000);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(counter("telemetry.valid")).isEqualTo(300);
            assertThat(counter("telemetry.invalid", "reason", "malformed_json")).isEqualTo(1);
            assertThat(counter("telemetry.invalid", "reason", "out_of_range")).isEqualTo(1);
            assertThat(counter("status.received", "state", "online")).isEqualTo(1);
            assertThat(counter("telemetry.stored", "result", "inserted")).isEqualTo(300);
            assertThat(counter("mqtt.acks", "result", "sent")).isEqualTo(303);
            assertThat(gauge("mqtt.acks.pending")).isZero();
        });
        assertThat(counter("mqtt.dropped", "reason", "sink_rejected")).isZero();
        assertThat(counter("mqtt.acks", "result", "failed")).isZero();
        assertThat(gauge("mqtt.connected")).isEqualTo(1);
        pub.disconnect().waitForCompletion(5000);
    }
}
