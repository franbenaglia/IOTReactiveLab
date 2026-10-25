package com.example.iotlab.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de las pruebas de integración: PostgreSQL y Mosquitto reales, iniciados una sola vez para
 * toda la corrida (patrón singleton; Ryuk los elimina al terminar la JVM). Todas las clases usan
 * la misma configuración, así Spring reutiliza un único contexto.
 *
 * <p>Consecuencia: los contadores Micrometer y las filas se acumulan entre pruebas. Cada prueba
 * usa sus propios deviceId y compara diferencias, no valores absolutos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "iotlab.pipeline.store-timeout=1s",
                "iotlab.pipeline.retry-max-backoff=1s", "iotlab.devices.last-seen-flush=500ms"})
public abstract class IntegrationTest {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    public static final MosquittoContainer MOSQUITTO = new MosquittoContainer();

    static {
        POSTGRES.start();
        MOSQUITTO.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry r) {
        // 127.0.0.1 y no "localhost": ver el comentario de spring.r2dbc.url en application.yml.
        String host = "localhost".equals(POSTGRES.getHost()) ? "127.0.0.1" : POSTGRES.getHost();
        String hostPort = host + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName();
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + hostPort);
        r.add("spring.r2dbc.username", POSTGRES::getUsername);
        r.add("spring.r2dbc.password", POSTGRES::getPassword);
        r.add("spring.flyway.url", () -> "jdbc:postgresql://" + hostPort);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("iotlab.mqtt.server-uri", MOSQUITTO::serverUri);
        r.add("iotlab.mqtt.password", () -> "");
        r.add("iotlab.mqtt.client-id", () -> "it-backend-app");
    }

    @Autowired protected MeterRegistry registry;
    @Autowired protected DatabaseClient db;

    protected double counter(String name, String... tags) {
        var c = registry.find("iotlab." + name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    protected double gauge(String name) {
        return registry.get("iotlab." + name).gauge().value();
    }

    protected long rows(String deviceIdPrefix) {
        return db.sql("SELECT count(*) AS n FROM telemetry WHERE device_id LIKE :p")
                .bind("p", deviceIdPrefix + "%")
                .map(r -> r.get("n", Long.class)).one().block();
    }

    protected static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    protected static MqttAsyncClient publisher(String clientId) throws MqttException {
        MqttAsyncClient pub = new MqttAsyncClient(MOSQUITTO.serverUri(), clientId, new MemoryPersistence());
        pub.connect().waitForCompletion(5000);
        return pub;
    }

    protected static void publish(MqttAsyncClient pub, String topic, String payload) throws MqttException {
        pub.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false).waitForCompletion(5000);
    }
}
