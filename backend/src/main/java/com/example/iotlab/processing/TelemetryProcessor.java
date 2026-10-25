package com.example.iotlab.processing;

import com.example.iotlab.config.PipelineProperties;
import com.example.iotlab.metrics.PipelineMetrics;
import com.example.iotlab.mqtt.Acknowledger;
import com.example.iotlab.mqtt.InboundMessage;
import com.example.iotlab.reactive.RealtimeTelemetryBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Pipeline A: recepción → validación → normalización → persistencia → confirmación (ADR-010).
 * Cada operador está explicado en docs/reactor-concepts.md.
 *
 * <p>Este método solo <i>arma</i> el pipeline (assembly): no ejecuta nada hasta que {@link
 * PipelineRunner} se suscribe, una sola vez, al arrancar la aplicación.
 */
@Component
public class TelemetryProcessor {

    private static final Logger log = LoggerFactory.getLogger(TelemetryProcessor.class);

    private final TelemetryParser parser;
    private final TelemetryStore store;
    private final SequenceTracker sequences;
    private final RealtimeTelemetryBus realtime;
    private final Acknowledger acknowledger;
    private final PipelineMetrics metrics;
    private final PipelineProperties props;

    public TelemetryProcessor(TelemetryParser parser, TelemetryStore store, SequenceTracker sequences,
                              RealtimeTelemetryBus realtime, Acknowledger acknowledger,
                              PipelineMetrics metrics, PipelineProperties props) {
        this.parser = parser;
        this.store = store;
        this.sequences = sequences;
        this.realtime = realtime;
        this.acknowledger = acknowledger;
        this.metrics = metrics;
        this.props = props;
    }

    public Flux<Outcome> process(Flux<InboundMessage> messages) {
        return messages
                // map: síncrono, en el hilo que entrega cada elemento (el callback de Paho, o el
                // event loop que completó una escritura y pidió más). Solo CPU: no bloquea.
                .map(parser::parse)
                // doOnNext: efectos laterales en el orden de llegada, antes de la concurrencia.
                .doOnNext(this::observe)
                // flatMap: hasta `concurrency` escrituras en curso a la vez. Concurrente, no
                // paralelo: R2DBC no ocupa un hilo por escritura. Los resultados salen en el
                // orden en que terminan, no en el de llegada; AckTracker reordena las
                // confirmaciones.
                .flatMap(this::handle, props.concurrency())
                // doOnNext: confirmar al broker y medir. Corre en el hilo que completó cada
                // escritura.
                .doOnNext(this::complete)
                // Defensa: handle() no propaga errores, así que esto no debería verse nunca. Si
                // pasara, el Flux terminaría: lo registramos para que no sea silencioso.
                .doOnError(e -> log.error("Pipeline A terminó con error (no debería ocurrir)", e));
    }

    private void observe(ParseResult r) {
        switch (r.kind()) {
            case TELEMETRY:
                metrics.receivedTelemetry();
                metrics.valid();
                if (sequences.observe(r.telemetry())) {
                    metrics.sequenceReset();
                }
                realtime.publish(r.telemetry()); // Pipelines B/C, best-effort
                break;
            case STATUS:
                metrics.receivedStatus();
                metrics.status(r.status().state() == com.example.iotlab.domain.DeviceStatus.State.ONLINE);
                break;
            case INVALID:
                if (r.reason() == InvalidReason.UNKNOWN_TOPIC) {
                    metrics.receivedUnknown();
                } else if (r.source().topic().endsWith("/status")) {
                    metrics.receivedStatus();
                } else {
                    metrics.receivedTelemetry();
                }
                metrics.invalid(r.reason());
                log.debug("Mensaje inválido: {}", r);
                break;
            default:
                throw new IllegalStateException(r.kind().toString());
        }
    }

    private Mono<Outcome> handle(ParseResult r) {
        InboundMessage src = r.source();
        switch (r.kind()) {
            case INVALID:
                // Reentregarlo no lo arregla: se confirma de inmediato.
                return Mono.just(new Outcome(Outcome.Type.INVALID, src.ackToken(), src.receivedNanos(), r.reason()));
            case STATUS:
                // Fase 3: se persiste en `devices`. Por ahora solo se cuenta.
                return Mono.just(new Outcome(Outcome.Type.STATUS, src.ackToken(), src.receivedNanos(), null));
            default:
                return persist(r);
        }
    }

    private Mono<Outcome> persist(ParseResult r) {
        InboundMessage src = r.source();
        return Mono.defer(() -> store.save(r.telemetry()))
                // retryWhen: ante un error transitorio, vuelve a suscribirse a store.save() con
                // espera exponencial (100 ms, 200 ms, ... hasta retryMaxBackoff) y sin límite de
                // intentos. Mientras tanto el mensaje no se confirma: con la base caída se llenan
                // las receiveMaximum ranuras y el broker deja de entregar (backpressure).
                .retryWhen(Retry.backoff(Long.MAX_VALUE, props.retryMinBackoff())
                        .maxBackoff(props.retryMaxBackoff())
                        .filter(store::isTransient)
                        .doBeforeRetry(s -> {
                            metrics.storeRetry();
                            if (s.totalRetries() % 10 == 0) {
                                log.warn("Reintentando persistencia de {} (intento {}): {}",
                                        r.telemetry().deviceId(), s.totalRetries() + 1, s.failure().toString());
                            }
                        }))
                .map(result -> new Outcome(result == StoreResult.INSERTED ? Outcome.Type.PERSISTED : Outcome.Type.DUPLICATE,
                        src.ackToken(), src.receivedNanos(), null))
                // onErrorResume: solo llegan aquí los errores NO transitorios (el filtro de
                // retryWhen los deja pasar). Se convierten en un Outcome: el Flux principal
                // nunca recibe onError por una medición.
                .onErrorResume(e -> {
                    log.error("Error no transitorio guardando {}: se descarta", r.telemetry(), e);
                    return Mono.just(new Outcome(Outcome.Type.FAILED, src.ackToken(), src.receivedNanos(), null));
                })
                // Gauge de escrituras en curso. doOnTerminate/doOnCancel y NO doFinally:
                // doFinally corre después de propagar la señal, cuando flatMap ya se suscribió a
                // la escritura siguiente, y el gauge mostraría concurrency + 1.
                .doOnSubscribe(s -> metrics.storeStarted())
                .doOnTerminate(metrics::storeFinished)
                .doOnCancel(metrics::storeFinished);
    }

    private void complete(Outcome o) {
        acknowledger.acknowledge(o.ackToken());
        switch (o.type()) {
            case PERSISTED: metrics.inserted(); break;
            case DUPLICATE: metrics.duplicate(); break;
            case FAILED: metrics.storeFailed(); break;
            default: break;
        }
        metrics.latency(o.receivedNanos());
    }
}
