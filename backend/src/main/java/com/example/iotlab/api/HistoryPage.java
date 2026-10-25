package com.example.iotlab.api;

import java.time.Instant;
import java.util.List;

/** Página del historial, en orden descendente por measuredAt. */
public record HistoryPage(String deviceId, Instant from, Instant to, int page, int size,
                          boolean hasMore, List<TelemetryView> items) {}
