package com.example.iotlab.reactive;

import com.example.iotlab.domain.DeviceStatus;
import com.example.iotlab.domain.Telemetry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Difusión en tiempo real para los Pipelines B (WebSocket) y C (alertas), separada de la
 * persistencia (ADR-010, punto 6). Lleva la telemetría válida y los cambios de estado.
 *
 * <p>{@code multicast().directBestEffort()}: cada suscriptor recibe lo que puede. Un suscriptor
 * sin demanda (un cliente WebSocket lento) pierde esos elementos, y los demás no se enteran. Con
 * {@code onBackpressureBuffer} un cliente lento haría crecer un buffer compartido; con
 * {@code directAllOrNothing}, frenaría a todos. Aquí perder actualizaciones intermedias es
 * aceptable (main.md §8, Pipeline B).
 *
 * <p><b>Hilos.</b> "direct" significa que {@code tryEmitNext} llama al {@code onNext} de cada
 * suscriptor en el hilo que emite: el del Pipeline A (Paho o un event loop). Los suscriptores
 * deben hacer poco trabajo síncrono antes de su primer operador asíncrono: filtrar, guardar en
 * un mapa. Lo pesado (serializar, escribir al socket) ocurre después, en otros hilos.
 */
@Component
public class RealtimeBus {

    private final Sinks.Many<Telemetry> telemetry = Sinks.many().multicast().directBestEffort();
    private final Sinks.Many<DeviceStatus> statuses = Sinks.many().multicast().directBestEffort();

    /** Sin suscriptores ({@code FAIL_ZERO_SUBSCRIBER}) el elemento se descarta: es lo esperado. */
    public void publish(Telemetry t) {
        telemetry.tryEmitNext(t);
    }

    public void publish(DeviceStatus s) {
        statuses.tryEmitNext(s);
    }

    public Flux<Telemetry> telemetry() {
        return telemetry.asFlux();
    }

    public Flux<DeviceStatus> statuses() {
        return statuses.asFlux();
    }

    public int subscribers() {
        return telemetry.currentSubscriberCount();
    }
}
