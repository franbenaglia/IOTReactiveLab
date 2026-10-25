package com.example.iotlab.api;

import java.time.Instant;

/**
 * Dispositivo con su última medición.
 *
 * @param lastSeen última telemetría recibida; se actualiza cada pocos segundos (ADR-013)
 */
public record DeviceView(String deviceId, String source, String status, Instant statusChangedAt,
                         Instant lastSeen, Instant createdAt, Double lastTemperature,
                         Double lastHumidity, Instant lastMeasuredAt) {}
