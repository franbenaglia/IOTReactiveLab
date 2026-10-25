package com.example.iotlab.api;

/**
 * GET /api/metrics/overview. Totales acumulados desde el arranque (contadores Micrometer) más
 * conteos de la base. Las tasas por segundo las calcula el cliente con dos lecturas sucesivas.
 */
public record MetricsOverview(
        boolean mqttConnected,
        double received,
        double valid,
        double invalid,
        double persisted,
        double duplicates,
        double failed,
        double storeRetries,
        double sequenceResets,
        double acksSent,
        double acksPending,
        double storeInFlight,
        double latencyP95Millis,
        double latencyP99Millis,
        long devicesTotal,
        long devicesOnline,
        long devicesOffline,
        long telemetryRows) {}
