package com.example.iotlab.reactive;

import com.example.iotlab.domain.Telemetry;
import com.example.iotlab.processing.PipelineRunner;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/**
 * Última medición de cada dispositivo, en memoria. Sirve para el {@code snapshot} inicial del
 * WebSocket (la vista se llena sin esperar al primer intervalo) y para la temperatura promedio
 * del overview, sin consultar la base.
 *
 * <p>Suscripción de larga duración al {@link RealtimeBus}: arranca antes que el pipeline, para
 * no perderse los primeros mensajes. Memoria: una entrada por dispositivo.
 */
@Component
public class LatestTelemetryCache implements SmartLifecycle {

    private final RealtimeBus bus;
    private final ConcurrentHashMap<String, Telemetry> latest = new ConcurrentHashMap<>();
    private volatile Disposable subscription;

    public LatestTelemetryCache(RealtimeBus bus) {
        this.bus = bus;
    }

    @Override
    public void start() {
        // Se queda con la de measuredAt mayor: una reentrega vieja no pisa a una nueva.
        subscription = bus.telemetry().subscribe(t -> latest.merge(t.deviceId(), t,
                (old, neu) -> neu.measuredAt().isBefore(old.measuredAt()) ? old : neu));
    }

    public List<Telemetry> latest(Predicate<String> deviceFilter) {
        return latest.values().stream().filter(t -> deviceFilter.test(t.deviceId())).toList();
    }

    public int size() {
        return latest.size();
    }

    @Override public void stop() { subscription.dispose(); }
    @Override public boolean isRunning() { return subscription != null && !subscription.isDisposed(); }
    @Override public int getPhase() { return PipelineRunner.PHASE - 50; }
}
