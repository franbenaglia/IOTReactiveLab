package com.example.iotlab.api;

import java.time.Instant;
import java.util.Map;

/**
 * GET /api/metrics/monitor: lectura instantánea de la instrumentación real para el Reactive
 * Monitor. Los contadores son acumulados; el cliente calcula tasas con dos lecturas.
 *
 * @param counters acumulados (mensajes, escrituras, errores…)
 * @param gauges valores instantáneos (en curso, pendientes, memoria, pool…). Un valor ausente
 *               significa que esa métrica no está disponible, nunca un cero inventado.
 */
public record MonitorSnapshot(Instant at, Map<String, Double> counters, Map<String, Double> gauges) {}
