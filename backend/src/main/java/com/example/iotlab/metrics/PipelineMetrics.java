package com.example.iotlab.metrics;

import com.example.iotlab.api.MetricsOverview;
import com.example.iotlab.processing.InvalidReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Métricas del pipeline (main.md §16). Todas las etiquetas tienen un conjunto acotado de
 * valores: nunca {@code deviceId}, que con 5.000 dispositivos crearía 5.000 series por métrica.
 * Los contadores se registran una vez y se reutilizan: el camino caliente no busca en el registry.
 */
@Component
public class PipelineMetrics {

    public static final String PREFIX = "iotlab.";

    private final MeterRegistry registry;
    private final Counter receivedTelemetry;
    private final Counter receivedStatus;
    private final Counter receivedUnknown;
    private final Counter valid;
    private final Map<InvalidReason, Counter> invalid = new EnumMap<>(InvalidReason.class);
    private final Counter inserted;
    private final Counter duplicates;
    private final Counter storeFailed;
    private final Counter storeRetries;
    private final Counter statusOnline;
    private final Counter statusOffline;
    private final Counter sequenceResets;
    private final Counter acksSent;
    private final Counter acksFailed;
    private final Counter acksDiscarded;
    private final Counter connectionsEstablished;
    private final Counter connectionsLost;
    private final Counter sinkDropped;
    private final Timer latency;
    private final AtomicInteger storeInFlight = new AtomicInteger();
    private final AtomicInteger connected = new AtomicInteger();

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
        receivedTelemetry = counter("mqtt.received", "Mensajes MQTT recibidos", "type", "telemetry");
        receivedStatus = counter("mqtt.received", "Mensajes MQTT recibidos", "type", "status");
        receivedUnknown = counter("mqtt.received", "Mensajes MQTT recibidos", "type", "unknown");
        valid = counter("telemetry.valid", "Mediciones que pasaron la validación");
        for (InvalidReason r : InvalidReason.values()) {
            invalid.put(r, counter("telemetry.invalid", "Mensajes rechazados por validación", "reason", r.tag()));
        }
        inserted = counter("telemetry.stored", "Resultado de la persistencia", "result", "inserted");
        duplicates = counter("telemetry.stored", "Resultado de la persistencia", "result", "duplicate");
        storeFailed = counter("telemetry.stored", "Resultado de la persistencia", "result", "failed");
        storeRetries = counter("telemetry.store.retries", "Reintentos de persistencia");
        statusOnline = counter("status.received", "Estados recibidos", "state", "online");
        statusOffline = counter("status.received", "Estados recibidos", "state", "offline");
        sequenceResets = counter("telemetry.sequence.resets", "Secuencias que retrocedieron (reinicio del dispositivo)");
        acksSent = counter("mqtt.acks", "Confirmaciones PUBACK", "result", "sent");
        acksFailed = counter("mqtt.acks", "Confirmaciones PUBACK", "result", "failed");
        acksDiscarded = counter("mqtt.acks", "Confirmaciones PUBACK", "result", "discarded");
        connectionsEstablished = counter("mqtt.connections", "Eventos de conexión MQTT", "event", "connected");
        connectionsLost = counter("mqtt.connections", "Eventos de conexión MQTT", "event", "lost");
        sinkDropped = counter("mqtt.dropped", "Mensajes que no entraron al Sink (no debería ocurrir)", "reason", "sink_rejected");
        latency = Timer.builder(PREFIX + "pipeline.latency")
                .description("Recepción MQTT → estado final (persistido, duplicado o inválido)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        Gauge.builder(PREFIX + "telemetry.store.inflight", storeInFlight, AtomicInteger::get)
                .description("Escrituras en curso dentro de flatMap")
                .register(registry);
        Gauge.builder(PREFIX + "mqtt.connected", connected, AtomicInteger::get)
                .description("1 si la conexión MQTT está activa")
                .register(registry);
    }

    private Counter counter(String name, String description, String... tags) {
        return Counter.builder(PREFIX + name).description(description).tags(tags).register(registry);
    }

    /** Gauge sobre un valor que mantiene otro componente (p. ej. PUBACK pendientes). */
    public void gauge(String name, String description, Supplier<Number> value) {
        Gauge.builder(PREFIX + name, value).description(description).register(registry);
    }

    public void receivedTelemetry() { receivedTelemetry.increment(); }
    public void receivedStatus() { receivedStatus.increment(); }
    public void receivedUnknown() { receivedUnknown.increment(); }
    public void valid() { valid.increment(); }
    public void invalid(InvalidReason reason) { invalid.get(reason).increment(); }
    public void inserted() { inserted.increment(); }
    public void duplicate() { duplicates.increment(); }
    public void storeFailed() { storeFailed.increment(); }
    public void storeRetry() { storeRetries.increment(); }
    public void status(boolean online) { (online ? statusOnline : statusOffline).increment(); }
    public void sequenceReset() { sequenceResets.increment(); }
    public void acksSent(int n) { acksSent.increment(n); }
    public void ackFailed() { acksFailed.increment(); }
    public void acksDiscarded(int n) { acksDiscarded.increment(n); }
    public void sinkDropped() { sinkDropped.increment(); }
    public void latency(long receivedNanos) { latency.record(Duration.ofNanos(System.nanoTime() - receivedNanos)); }
    public void storeStarted() { storeInFlight.incrementAndGet(); }
    public void storeFinished() { storeInFlight.decrementAndGet(); }

    public void connected() {
        connected.set(1);
        connectionsEstablished.increment();
    }

    public void connectionLost() {
        if (connected.getAndSet(0) == 1) {
            connectionsLost.increment();
        }
    }

    public boolean isConnected() { return connected.get() == 1; }

    public MetricsOverview overview(long devicesTotal, long devicesOnline, long devicesOffline, long telemetryRows) {
        double invalidTotal = invalid.values().stream().mapToDouble(Counter::count).sum();
        var snapshot = latency.takeSnapshot();
        double p95 = 0, p99 = 0;
        for (var v : snapshot.percentileValues()) {
            if (v.percentile() == 0.95) p95 = v.value(java.util.concurrent.TimeUnit.MILLISECONDS);
            if (v.percentile() == 0.99) p99 = v.value(java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        var pending = registry.find(PREFIX + "mqtt.acks.pending").gauge();
        return new MetricsOverview(isConnected(),
                receivedTelemetry.count() + receivedStatus.count() + receivedUnknown.count(),
                valid.count(), invalidTotal, inserted.count(), duplicates.count(), storeFailed.count(),
                storeRetries.count(), sequenceResets.count(), acksSent.count(),
                pending == null ? 0 : pending.value(), storeInFlight.get(), p95, p99,
                devicesTotal, devicesOnline, devicesOffline, telemetryRows);
    }
}
