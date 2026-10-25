package com.example.iotlab.api;

import java.time.Instant;

/** Medición tal como la expone la API REST. */
public record TelemetryView(String deviceId, double temperature, Double humidity,
                            Instant measuredAt, Instant receivedAt, long sequence) {}
