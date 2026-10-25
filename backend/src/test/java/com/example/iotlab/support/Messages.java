package com.example.iotlab.support;

import com.example.iotlab.mqtt.InboundMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Mensajes de prueba que respetan (o violan a propósito) docs/mqtt-contract.md. */
public final class Messages {

    private static final AtomicLong TOKENS = new AtomicLong();

    private Messages() { }

    /** Hora fija de las pruebas unitarias (que usan un Clock fijo a las 12:31Z). */
    public static final String FIXED_TIMESTAMP = "2026-10-25T12:30:00.123Z";

    public static String telemetryJson(String deviceId, double temperature, long sequence) {
        return telemetryJson(deviceId, temperature, sequence, FIXED_TIMESTAMP);
    }

    /** Para pruebas con el reloj real: usar Instant.now(), o el validador lo ve "del futuro". */
    public static String telemetryJson(String deviceId, double temperature, long sequence, String timestamp) {
        return "{\"deviceId\":\"" + deviceId + "\",\"temperature\":" + temperature
                + ",\"humidity\":null,\"timestamp\":\"" + timestamp + "\",\"sequence\":" + sequence
                + ",\"source\":\"SIMULATED\"}";
    }

    public static InboundMessage inbound(String topic, String payload) {
        return new InboundMessage(topic, payload.getBytes(StandardCharsets.UTF_8), TOKENS.incrementAndGet(),
                Instant.parse("2026-10-25T12:30:01Z"), System.nanoTime());
    }

    public static InboundMessage telemetry(String deviceId, double temperature, long sequence) {
        return inbound("iot/devices/" + deviceId + "/telemetry", telemetryJson(deviceId, temperature, sequence));
    }
}
