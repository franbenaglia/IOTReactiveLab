package com.example.iotlab.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Conexión MQTT del backend (ADR-009, ADR-010).
 *
 * @param receiveMaximum mensajes QoS 1 sin confirmar que el broker puede entregar a la vez.
 *                       Es la perilla de backpressure: con la base lenta, se llenan y el broker
 *                       deja de enviar.
 * @param sessionExpirySeconds cuánto conserva el broker la sesión (y lo no confirmado) si el
 *                       backend está caído.
 */
@ConfigurationProperties("iotlab.mqtt")
public record MqttProperties(
        @DefaultValue("tcp://localhost:1883") String serverUri,
        @DefaultValue("iotlab-backend") String clientId,
        @DefaultValue("backend") String username,
        @DefaultValue("") String password,
        @DefaultValue("100") int receiveMaximum,
        @DefaultValue("3600") long sessionExpirySeconds,
        @DefaultValue("30") int keepAliveSeconds,
        @DefaultValue("10") int connectTimeoutSeconds,
        @DefaultValue({"iot/devices/+/telemetry", "iot/devices/+/status"}) List<String> topics) {

    public MqttProperties {
        if (receiveMaximum < 1 || receiveMaximum > 65535) {
            throw new IllegalArgumentException("iotlab.mqtt.receive-maximum debe estar en [1, 65535]");
        }
    }
}
