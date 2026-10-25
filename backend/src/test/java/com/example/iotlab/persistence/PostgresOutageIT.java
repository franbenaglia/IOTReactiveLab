package com.example.iotlab.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.iotlab.support.IntegrationTest;
import com.example.iotlab.support.Messages;
import java.time.Duration;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Experimento 4 en miniatura (main.md §13): PostgreSQL deja de responder durante la carga. Se
 * congela el contenedor con `docker pause`: las conexiones quedan colgadas, sin error, que es el
 * caso más difícil. Espera: reintentos, nada se confirma, y al volver se guarda todo sin pérdidas.
 */
class PostgresOutageIT extends IntegrationTest {

    @Test
    void frozenDatabaseCausesRetriesAndBackpressureThenEverythingIsPersisted() throws Exception {
        var docker = DockerClientFactory.instance().client();
        double retries0 = counter("telemetry.store.retries");
        MqttAsyncClient pub = publisher("it-outage-sim");
        // Asegura que el dispositivo ya está dado de alta antes de la caída.
        publish(pub, "iot/devices/outage-001/telemetry", Messages.telemetryJson("outage-001", 20, 0, now()));
        await().atMost(Duration.ofSeconds(10)).until(() -> rows("outage-") == 1);

        docker.pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            for (int i = 1; i <= 200; i++) {
                publish(pub, "iot/devices/outage-001/telemetry", Messages.telemetryJson("outage-001", 20, i, now()));
            }
            // Con la base congelada: hay reintentos (timeout de 1 s por intento) y mensajes sin
            // confirmar retenidos.
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(counter("telemetry.store.retries") - retries0).isPositive();
                assertThat(gauge("mqtt.acks.pending")).isPositive();
            });
            // Backpressure: nunca hay más de receiveMaximum (100) sin confirmar, aunque se
            // publicaron 200.
            assertThat(gauge("mqtt.acks.pending")).isLessThanOrEqualTo(100);
        } finally {
            docker.unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(rows("outage-")).isEqualTo(201);
            assertThat(gauge("mqtt.acks.pending")).isZero();
        });
        assertThat(counter("telemetry.stored", "result", "failed")).isZero();
        pub.disconnect().waitForCompletion(5000);
    }
}
