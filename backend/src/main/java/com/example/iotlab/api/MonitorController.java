package com.example.iotlab.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Datos del Reactive Monitor (main.md §12). Todo sale del MeterRegistry: lo que no esté
 * registrado no aparece en la respuesta.
 *
 * <p>Lectura de métricas en memoria: CPU pura y rápida, por eso {@code Mono.fromSupplier} sin
 * cambiar de hilo.
 */
@RestController
public class MonitorController {

    private final MeterRegistry registry;

    public MonitorController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/metrics/monitor")
    public Mono<MonitorSnapshot> monitor() {
        return Mono.fromSupplier(this::snapshot);
    }

    MonitorSnapshot snapshot() {
        Map<String, Double> c = new LinkedHashMap<>();
        counter(c, "mqttReceived", "iotlab.mqtt.received");
        counter(c, "valid", "iotlab.telemetry.valid");
        counter(c, "invalid", "iotlab.telemetry.invalid");
        counter(c, "persisted", "iotlab.telemetry.stored", "result", "inserted");
        counter(c, "duplicates", "iotlab.telemetry.stored", "result", "duplicate");
        counter(c, "persistFailed", "iotlab.telemetry.stored", "result", "failed");
        counter(c, "persistRetries", "iotlab.telemetry.store.retries");
        counter(c, "sinkDropped", "iotlab.mqtt.dropped");
        counter(c, "acksSent", "iotlab.mqtt.acks", "result", "sent");
        counter(c, "acksDiscarded", "iotlab.mqtt.acks", "result", "discarded");
        counter(c, "status", "iotlab.status.received");
        counter(c, "alertsRaised", "iotlab.alerts", "state", "raised");
        counter(c, "wsMessages", "iotlab.ws.messages");
        counter(c, "wsDropped", "iotlab.ws.dropped");
        counter(c, "reactorErrorsDropped", "iotlab.reactor.errors.dropped");
        counter(c, "mqttConnectionsLost", "iotlab.mqtt.connections", "event", "lost");
        timerCount(c, "processed", "iotlab.pipeline.latency");

        Map<String, Double> g = new LinkedHashMap<>();
        gauge(g, "mqttConnected", "iotlab.mqtt.connected");
        gauge(g, "acksPending", "iotlab.mqtt.acks.pending");
        gauge(g, "sinkQueued", "iotlab.mqtt.sink.queued");
        gauge(g, "storeInFlight", "iotlab.telemetry.store.inflight");
        gauge(g, "alertsActive", "iotlab.alerts.active");
        gauge(g, "wsClients", "iotlab.ws.clients");
        gauge(g, "r2dbcAcquired", "r2dbc.pool.acquired");
        gauge(g, "r2dbcIdle", "r2dbc.pool.idle");
        gauge(g, "r2dbcPending", "r2dbc.pool.pending");
        gauge(g, "r2dbcMaxAllocated", "r2dbc.pool.max.allocated");
        sumGauge(g, "jvmHeapUsed", "jvm.memory.used", "area", "heap");
        sumGauge(g, "jvmHeapMax", "jvm.memory.max", "area", "heap");
        sumGauge(g, "jvmNonHeapUsed", "jvm.memory.used", "area", "nonheap");
        gauge(g, "processCpu", "process.cpu.usage");
        gauge(g, "jvmThreads", "jvm.threads.live");
        sumGauge(g, "nettyConnectionsActive", "reactor.netty.http.server.connections.active");
        sumGauge(g, "nettyDirectMemoryUsed", "reactor.netty.bytebuf.allocator.used.direct.memory");
        percentile(g, "latencyP50Ms", 0.5);
        percentile(g, "latencyP95Ms", 0.95);
        percentile(g, "latencyP99Ms", 0.99);
        return new MonitorSnapshot(Instant.now(), c, g);
    }

    /** Suma todas las series del nombre (p. ej. todos los motivos de invalid). */
    private void counter(Map<String, Double> out, String key, String name, String... tags) {
        var meters = registry.find(name).tags(Tags.of(tags)).counters();
        if (!meters.isEmpty()) {
            out.put(key, meters.stream().mapToDouble(Counter::count).sum());
        }
    }

    private void timerCount(Map<String, Double> out, String key, String name) {
        Timer t = registry.find(name).timer();
        if (t != null) {
            out.put(key, (double) t.count());
        }
    }

    private void gauge(Map<String, Double> out, String key, String name) {
        Gauge gauge = registry.find(name).gauge();
        if (gauge != null && !Double.isNaN(gauge.value())) {
            out.put(key, gauge.value());
        }
    }

    private void sumGauge(Map<String, Double> out, String key, String name, String... tags) {
        var gauges = registry.find(name).tags(Tags.of(tags)).gauges();
        if (!gauges.isEmpty()) {
            out.put(key, gauges.stream().mapToDouble(Gauge::value).filter(v -> !Double.isNaN(v) && v >= 0).sum());
        }
    }

    private void percentile(Map<String, Double> out, String key, double p) {
        Timer t = registry.find("iotlab.pipeline.latency").timer();
        if (t == null) {
            return;
        }
        for (ValueAtPercentile v : t.takeSnapshot().percentileValues()) {
            if (v.percentile() == p) {
                out.put(key, v.value(TimeUnit.MILLISECONDS));
            }
        }
    }
}
