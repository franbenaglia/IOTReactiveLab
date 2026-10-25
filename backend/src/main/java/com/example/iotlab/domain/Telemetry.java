package com.example.iotlab.domain;

import java.time.Instant;

/**
 * Medición validada y normalizada. Inmutable: viaja entre hilos dentro del pipeline.
 *
 * @param humidity nula si el sensor no mide humedad (contrato MQTT)
 * @param measuredAt momento de la medición según el dispositivo
 * @param receivedAt momento en que la recibió el backend
 */
public record Telemetry(
        String deviceId,
        double temperature,
        Double humidity,
        Instant measuredAt,
        long sequence,
        Source source,
        Instant receivedAt) {}
