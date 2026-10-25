package com.example.iotlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.iotlab.mqtt.MqttHealthIndicator;
import com.example.iotlab.support.IntegrationTest;
import com.example.iotlab.support.Messages;
import java.time.Duration;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;

/** De punta a punta: MQTT → Pipeline A → PostgreSQL → PUBACK. */
class BackendMqttIT extends IntegrationTest {

    @Autowired MqttHealthIndicator health;

    @Test
    void persistsValidMessagesDeduplicatesRedeliveriesAndAcksEverything() throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(() -> health.health().getStatus().equals(Status.UP));
        double valid0 = counter("telemetry.valid");
        double inserted0 = counter("telemetry.stored", "result", "inserted");
        double dup0 = counter("telemetry.stored", "result", "duplicate");
        double acks0 = counter("mqtt.acks", "result", "sent");
        double invalid0 = counter("telemetry.invalid", "reason", "malformed_json");

        MqttAsyncClient pub = publisher("it-e2e-sim");
        String[] payloads = new String[300];
        for (int i = 0; i < 300; i++) {
            String id = String.format("e2e-%03d", i % 10);
            payloads[i] = Messages.telemetryJson(id, 20 + i % 5, i, now());
            publish(pub, "iot/devices/" + id + "/telemetry", payloads[i]);
        }
        // 20 reenvíos idénticos: lo que haría un productor que no recibió el PUBACK.
        for (int i = 0; i < 20; i++) {
            String id = String.format("e2e-%03d", i % 10);
            publish(pub, "iot/devices/" + id + "/telemetry", payloads[i]);
        }
        publish(pub, "iot/devices/e2e-000/telemetry", "{roto");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(counter("telemetry.valid") - valid0).isEqualTo(320);
            assertThat(counter("telemetry.stored", "result", "inserted") - inserted0).isEqualTo(300);
            assertThat(counter("telemetry.stored", "result", "duplicate") - dup0).isEqualTo(20);
            assertThat(counter("telemetry.invalid", "reason", "malformed_json") - invalid0).isEqualTo(1);
            assertThat(counter("mqtt.acks", "result", "sent") - acks0).isEqualTo(321);
            assertThat(gauge("mqtt.acks.pending")).isZero();
        });
        assertThat(rows("e2e-")).isEqualTo(300);
        pub.disconnect().waitForCompletion(5000);
    }
}
