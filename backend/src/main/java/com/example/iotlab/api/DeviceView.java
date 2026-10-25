package com.example.iotlab.api;

import java.time.Instant;

/**
 * Dispositivo con su última medición.
 *
 * @param lastSeen última telemetría recibida; se actualiza cada pocos segundos (ADR-013)
 * @param messagesLastMinute mediciones con measuredAt en los últimos 60 s: frecuencia estimada
 */
public record DeviceView(String deviceId, String source, String status, Instant statusChangedAt,
                         Instant lastSeen, Instant createdAt, Double lastTemperature,
                         Double lastHumidity, Instant lastMeasuredAt, Long messagesLastMinute) {}
