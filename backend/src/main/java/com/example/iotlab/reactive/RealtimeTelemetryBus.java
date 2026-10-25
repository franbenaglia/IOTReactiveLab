package com.example.iotlab.reactive;

import com.example.iotlab.domain.Telemetry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Difusión en tiempo real para los Pipelines B (WebSocket) y C (alertas), separada de la
 * persistencia (ADR-010, punto 6).
 *
 * <p>{@code multicast().directBestEffort()}: cada suscriptor recibe lo que puede. Un suscriptor
 * sin demanda (un cliente WebSocket lento) pierde esos elementos, y los demás no se enteran. Con
 * {@code onBackpressureBuffer} un cliente lento haría crecer un buffer compartido; con
 * {@code directAllOrNothing}, frenaría a todos. Aquí perder actualizaciones intermedias es
 * aceptable (main.md §8, Pipeline B).
 */
@Component
public class RealtimeTelemetryBus {

    private final Sinks.Many<Telemetry> sink = Sinks.many().multicast().directBestEffort();

    /** Sin suscriptores ({@code FAIL_ZERO_SUBSCRIBER}) el elemento se descarta: es lo esperado. */
    public void publish(Telemetry telemetry) {
        sink.tryEmitNext(telemetry);
    }

    public Flux<Telemetry> flux() {
        return sink.asFlux();
    }

    public int subscribers() {
        return sink.currentSubscriberCount();
    }
}
