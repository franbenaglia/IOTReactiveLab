package com.example.iotlab.alerts;

import com.example.iotlab.metrics.PipelineMetrics;
import com.example.iotlab.processing.PipelineRunner;
import com.example.iotlab.reactive.RealtimeBus;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Pipeline C: telemetría → {@link AlertDetector} → eventos de alerta → WebSocket.
 *
 * <p>{@code handle} combina map y filter: por cada medición emite cero o un evento. Las alertas
 * activas se guardan en memoria (GET /api/alerts); si el backend reinicia, se recalculan con las
 * mediciones siguientes.
 */
@Component
public class AlertService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final RealtimeBus bus;
    private final AlertDetector detector;
    private final PipelineMetrics metrics;
    private final Sinks.Many<Alert> events = Sinks.many().multicast().directBestEffort();
    private final ConcurrentHashMap<String, Alert> active = new ConcurrentHashMap<>();
    private volatile Disposable subscription;

    public AlertService(RealtimeBus bus, AlertProperties props, PipelineMetrics metrics) {
        this.bus = bus;
        this.detector = new AlertDetector(props);
        this.metrics = metrics;
        metrics.gauge("alerts.active", "Alertas activas", active::size);
    }

    @Override
    public void start() {
        subscription = bus.telemetry()
                .<Alert>handle((t, sink) -> detector.evaluate(t).ifPresent(sink::next))
                .subscribe(this::onAlert, e -> log.error("Pipeline C terminó con error", e));
    }

    private void onAlert(Alert a) {
        if (a.state() == Alert.State.RAISED) {
            active.put(a.deviceId(), a);
            metrics.alertRaised();
        } else {
            active.remove(a.deviceId());
            metrics.alertCleared();
        }
        events.tryEmitNext(a);
    }

    public Flux<Alert> events() {
        return events.asFlux();
    }

    public List<Alert> active() {
        return List.copyOf(active.values());
    }

    @Override public void stop() { subscription.dispose(); }
    @Override public boolean isRunning() { return subscription != null && !subscription.isDisposed(); }
    @Override public int getPhase() { return PipelineRunner.PHASE - 50; }
}
