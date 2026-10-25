package com.example.iotlab.processing;

import com.example.iotlab.config.PipelineProperties;
import com.example.iotlab.reactive.MqttReactorAdapter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/**
 * Único punto donde se suscribe el Pipeline A (CLAUDE.md §3: nada de subscribe() sueltos).
 *
 * <p>Orden de arranque y parada con {@link SmartLifecycle}: este bean está en una fase menor que
 * {@code MqttSubscriber}, así que arranca <b>antes</b> (el pipeline ya escucha cuando llega el
 * primer mensaje) y se detiene <b>después</b> (MQTT se desconecta primero, y luego se vacía lo
 * que quedó en curso).
 */
@Component
public class PipelineRunner implements SmartLifecycle {

    public static final int PHASE = 0;
    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private final MqttReactorAdapter adapter;
    private final TelemetryProcessor processor;
    private final PipelineProperties props;
    private volatile Disposable subscription;
    private volatile CountDownLatch terminated;

    public PipelineRunner(MqttReactorAdapter adapter, TelemetryProcessor processor, PipelineProperties props) {
        this.adapter = adapter;
        this.processor = processor;
        this.props = props;
    }

    @Override
    public void start() {
        CountDownLatch latch = new CountDownLatch(1);
        terminated = latch;
        subscription = processor.process(adapter.messages())
                .doFinally(signal -> latch.countDown())
                .subscribe(
                        outcome -> { },
                        error -> log.error("Pipeline A terminado por error", error),
                        () -> log.info("Pipeline A completado"));
        log.info("Pipeline A suscripto (concurrencia={}, cola del Sink={})", props.concurrency(), adapter.capacity());
    }

    @Override
    public void stop() {
        // MQTT ya se desconectó (fase mayor). Completar el Sink deja que flatMap termine lo que
        // tiene en curso; si no termina en el plazo, se cancela. Lo no confirmado lo reentrega
        // el broker en el próximo arranque y la base descarta los duplicados (ADR-010/011).
        adapter.complete();
        try {
            if (!terminated.await(props.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Pipeline A no terminó en {}: se cancela", props.shutdownTimeout());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        subscription.dispose();
    }

    @Override
    public boolean isRunning() {
        return subscription != null && !subscription.isDisposed();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
