package com.example.iotlab.persistence;

import com.example.iotlab.processing.PipelineRunner;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Escribe periódicamente los last_seen acumulados en {@link DeviceRegistry}.
 *
 * <p>{@code Flux.interval} + {@code concatMap}: concatMap espera que termine un flush antes de
 * empezar el siguiente. Con flatMap, una base lenta acumularía flushes superpuestos peleando por
 * las mismas filas. {@code interval} emite en {@code Schedulers.parallel()}; si un flush tarda
 * más que el período, los ticks se acumulan y {@code onBackpressureDrop} descarta los sobrantes
 * (sin él, interval falla con OverflowException).
 *
 * <p>Fase de ciclo de vida menor que el pipeline: arranca antes y se detiene después, con un
 * flush final.
 */
@Component
public class DeviceRegistryFlusher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistryFlusher.class);

    private final DeviceRegistry registry;
    private final Duration period;
    private volatile Disposable subscription;

    public DeviceRegistryFlusher(DeviceRegistry registry,
                                 @Value("${iotlab.devices.last-seen-flush:5s}") Duration period) {
        this.registry = registry;
        this.period = period;
    }

    @Override
    public void start() {
        subscription = Flux.interval(period, period)
                .onBackpressureDrop()
                .concatMap(tick -> registry.flush().onErrorResume(e -> Mono.just(0L)))
                .subscribe();
    }

    @Override
    public void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
        // Hilo de apagado (no es un event loop): bloquear acá es aceptable y es la única forma
        // de garantizar el último flush antes de cerrar el pool de conexiones.
        try {
            registry.flush().block(Duration.ofSeconds(5));
        } catch (RuntimeException e) {
            log.warn("Flush final de last_seen falló: {}", e.toString());
        }
    }

    @Override
    public boolean isRunning() {
        return subscription != null && !subscription.isDisposed();
    }

    @Override
    public int getPhase() {
        return PipelineRunner.PHASE - 100;
    }
}
